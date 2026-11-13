package com.agentforge.workflow;

import com.agentforge.agent.*;
import com.agentforge.agent.Agent.Reasoned;
import com.agentforge.agent.Models.*;
import com.agentforge.ai.AiService;
import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.common.Json;
import com.agentforge.common.Text;
import com.agentforge.events.EventService;
import com.agentforge.evidence.EvidenceService;
import com.agentforge.geo.GeoService;
import com.agentforge.memory.MemoryService;
import com.agentforge.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Runs the multi-agent workflow:
 * plan → parallel specialist analysis → critic review → debate/revision → (human checkpoints) → synthesis.
 * Every step is persisted as an event, which drives the Live Agent Radar and the Decision Trace Timeline.
 */
@Service
public class Orchestrator {
    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final Db db;
    private final AiService ai;
    private final AgentTeam team;
    private final EventService events;
    private final TaskService tasks;
    private final EvidenceService evidence;
    private final MemoryService memory;
    private final GeoService geo;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, Gate> gates = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    /** A paused run waiting for the user. */
    public record Gate(String checkpoint, int version, String message, Object payload, CompletableFuture<Decision> future) {
    }

    private static class StopRun extends RuntimeException {
        StopRun(String m) {
            super(m);
        }
    }

    /** Mutable per-run state. */
    private final class Run {
        final long taskId;
        final long uid;
        final int version;
        final String engine;
        final Set<String> checkpoints;
        final AgentContext ctx;
        final List<String> engineNotes = Collections.synchronizedList(new ArrayList<>());
        final Map<AgentRole, AgentState> states = new ConcurrentHashMap<>();

        Run(long taskId, long uid, int version, String engine, Set<String> checkpoints, AgentContext ctx) {
            this.taskId = taskId;
            this.uid = uid;
            this.version = version;
            this.engine = engine;
            this.checkpoints = checkpoints;
            this.ctx = ctx;
        }
    }

    public Orchestrator(Db db, AiService ai, AgentTeam team, EventService events, TaskService tasks,
                        EvidenceService evidence, MemoryService memory, GeoService geo) {
        this.db = db;
        this.ai = ai;
        this.team = team;
        this.events = events;
        this.tasks = tasks;
        this.evidence = evidence;
        this.memory = memory;
        this.geo = geo;
    }

    /** Runs that were in flight when the server stopped cannot be resumed; mark them honestly. */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        for (Map<String, Object> t : db.list("SELECT id FROM tasks WHERE status IN ('running','awaiting_approval')")) {
            long id = Db.lng(t, "id");
            tasks.setStatus(id, "interrupted");
            events.emit(id, null, "status", null, null, "Run interrupted by a server restart. Start it again to continue.", Map.of("status", "interrupted"));
        }
    }

    // ------------------------------------------------------------------ public API

    public Map<String, Object> start(long taskId, long uid) {
        Map<String, Object> t = tasks.owned(taskId, uid);
        String status = Db.str(t, "status");
        if ("running".equals(status) || "awaiting_approval".equals(status)) throw ApiException.conflict("Analysis is already running");
        int version = nextVersion(taskId);
        db.insert("versions", Db.row("task_id", taskId, "version_no", version, "status", "running",
                "change_note", version == 1 ? "Initial analysis" : "Full re-run"));
        launch(taskId, uid, version, null, null);
        return Map.of("taskId", taskId, "version", version);
    }

    /** What-If Simulator: re-runs the analysis with changed assumptions as a new version. */
    public Map<String, Object> whatIf(long taskId, long uid, List<String> assumptions, String note) {
        Map<String, Object> t = tasks.owned(taskId, uid);
        if (!"completed".equals(Db.str(t, "status"))) throw ApiException.conflict("Run What-If only on a completed analysis");
        List<String> cleaned = assumptions == null ? List.of() : assumptions.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (cleaned.isEmpty()) throw ApiException.bad("Provide at least one assumption");
        int parent = ((Number) t.get("current_version")).intValue();
        Map<String, Object> pv = db.one("SELECT * FROM versions WHERE task_id=? AND version_no=?", taskId, parent).orElseThrow();
        int version = nextVersion(taskId);
        db.insert("versions", Db.row("task_id", taskId, "version_no", version, "parent_version", parent, "status", "running",
                "change_note", note == null || note.isBlank() ? "What-If scenario" : note.trim(), "assumptions", Json.write(cleaned)));
        launch(taskId, uid, version, Db.str(pv, "plan_json"), cleaned);
        return Map.of("taskId", taskId, "version", version, "parentVersion", parent);
    }

    public void requestPause(long taskId, long uid) {
        Map<String, Object> t = tasks.owned(taskId, uid);
        if (!"running".equals(Db.str(t, "status"))) throw ApiException.conflict("Only a running analysis can be paused");
        db.update("UPDATE tasks SET pause_requested=TRUE WHERE id=?", taskId);
        events.emit(taskId, ((Number) t.get("current_version")).intValue(), "pause_requested", "user", null,
                "User requested a pause at the next checkpoint", null);
    }

    public void cancel(long taskId, long uid) {
        Map<String, Object> t = tasks.owned(taskId, uid);
        String st = Db.str(t, "status");
        if (!"running".equals(st) && !"awaiting_approval".equals(st)) throw ApiException.conflict("Nothing to stop");
        cancelled.add(taskId);
        Gate g = gates.get(taskId);
        if (g != null) g.future().complete(new Decision("stop", "Stopped by user", Map.of()));
    }

    public void decide(long taskId, long uid, Decision d) {
        tasks.owned(taskId, uid);
        Gate g = gates.get(taskId);
        if (g == null) throw ApiException.conflict("This analysis is not waiting for a decision");
        if (!"approve".equals(d.action()) && !"stop".equals(d.action())) throw ApiException.bad("Action must be approve or stop");
        if ("on_conflict".equals(g.checkpoint()) && d.approve()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pending = (List<Map<String, Object>>) ((Map<String, Object>) g.payload()).get("conflicts");
            for (Map<String, Object> c : pending) {
                String choice = d.conflictChoices() == null ? null : d.conflictChoices().get(Db.lng(c, "id"));
                if (!"uphold".equals(choice) && !"reject".equals(choice))
                    throw ApiException.bad("Choose uphold or reject for every unresolved conflict");
            }
        }
        if (d.approve()) tasks.setStatus(taskId, "running");
        g.future().complete(d);
    }

    public Optional<Gate> gate(long taskId) {
        return Optional.ofNullable(gates.get(taskId));
    }

    // ------------------------------------------------------------------ run

    private int nextVersion(long taskId) {
        return db.one("SELECT COALESCE(MAX(version_no),0)+1 AS n FROM versions WHERE task_id=?", taskId)
                .map(r -> ((Number) r.get("n")).intValue()).orElse(1);
    }

    private void launch(long taskId, long uid, int version, String parentPlanJson, List<String> overrideAssumptions) {
        String engine = ai.available() ? "claude" : "rules";
        db.update("UPDATE tasks SET status='running', engine=?, current_version=?, pause_requested=FALSE, error=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                engine, version, taskId);
        cancelled.remove(taskId);
        events.pushUser(uid, "task-status", Map.of("taskId", taskId, "status", "running"));
        pool.submit(() -> {
            try {
                execute(taskId, uid, version, engine, parentPlanJson, overrideAssumptions);
            } catch (StopRun s) {
                db.update("UPDATE versions SET status='stopped' WHERE task_id=? AND version_no=?", taskId, version);
                tasks.setStatus(taskId, "stopped");
                events.emit(taskId, version, "status", null, null, "Run stopped: " + s.getMessage(), Map.of("status", "stopped"));
                events.pushUser(uid, "task-status", Map.of("taskId", taskId, "status", "stopped"));
            } catch (Exception e) {
                log.error("Run failed for task {}", taskId, e);
                db.update("UPDATE tasks SET status='failed', error=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", e.getMessage(), taskId);
                db.update("UPDATE versions SET status='failed' WHERE task_id=? AND version_no=?", taskId, version);
                events.emit(taskId, version, "error", null, null, "Analysis failed: " + e.getMessage(), Map.of("status", "failed"));
                events.notifyUser(uid, taskId, "failed", "Analysis failed: " + Text.truncate(e.getMessage(), 200));
            } finally {
                gates.remove(taskId);
                cancelled.remove(taskId);
            }
        });
    }

    private void execute(long taskId, long uid, int version, String engine, String parentPlanJson, List<String> overrideAssumptions) {
        Map<String, Object> task = tasks.get(taskId);
        Set<String> checkpoints = new HashSet<>(Arrays.asList(Objects.toString(task.get("checkpoints"), "").split(",")));
        checkpoints.remove("");
        String title = Db.str(task, "title");
        String description = Db.str(task, "description");
        String locationText = Db.str(task, "location_text");
        boolean hasLocation = locationText != null || task.get("lat") != null;

        List<MemoryHit> mem = memory.retrieve(uid, title + " " + description, taskId, 4);
        AgentContext ctx = new AgentContext(taskId, version, title, description, null,
                overrideAssumptions == null ? List.of() : overrideAssumptions, evidence.activeItems(taskId), mem);
        ctx.setScenario(overrideAssumptions != null);
        ctx.setIntensity(Db.str(task, "debate_intensity"));
        Run run = new Run(taskId, uid, version, engine, checkpoints, ctx);

        events.emit(taskId, version, "run_started", null, null,
                (overrideAssumptions == null ? "Analysis v" : "What-If scenario v") + version + " started using the "
                        + ("claude".equals(engine) ? "Claude (" + ai.model() + ")" : "rule-based") + " engine",
                Map.of("engine", engine, "evidence", ctx.evidence().size(), "checkpoints", checkpoints, "intensity", ctx.intensity()));
        for (AgentRole r : AgentRole.values()) state(run, r, AgentState.IDLE, null);
        for (MemoryHit h : mem) {
            memory.recordUse(taskId, version, h);
            events.emit(taskId, version, "memory_reused", "coordinator", null,
                    "Reusing memory #" + h.id() + ": " + Text.truncate(h.content(), 90), Map.of("memoryId", h.id(), "score", h.score()));
        }

        // ---- Stage 1: plan
        Plan plan;
        if (parentPlanJson == null) {
            state(run, AgentRole.COORDINATOR, AgentState.PROCESSING, "Breaking down the task");
            Reasoned<Plan> rp = team.coordinator().plan(ctx, hasLocation, engine);
            noteEngine(run, AgentRole.COORDINATOR, rp);
            plan = rp.value();
            ctx.setAssumptions(plan.assumptions());
        } else {
            JsonNode pj = Json.read(parentPlanJson);
            Map<String, String> sub = new LinkedHashMap<>();
            pj.path("subtasks").fields().forEachRemaining(e -> sub.put(e.getKey(), e.getValue().asText()));
            List<String> geoQ = new ArrayList<>();
            pj.path("geoQueries").forEach(n -> geoQ.add(n.asText()));
            plan = new Plan(pj.path("summary").asText(""), overrideAssumptions, sub, pj.path("locationRelevant").asBoolean(false), geoQ);
            state(run, AgentRole.COORDINATOR, AgentState.PROCESSING, "Applying changed assumptions");
            events.emit(taskId, version, "assumptions_changed", "user", "coordinator", "Assumptions changed for What-If scenario",
                    Map.of("assumptions", overrideAssumptions));
        }
        ctx.setPlan(plan);
        db.update("UPDATE versions SET plan_json=?, assumptions=? WHERE task_id=? AND version_no=?",
                Json.write(plan), Json.write(plan.assumptions()), taskId, version);
        events.emit(taskId, version, "plan", "coordinator", null, "Plan: " + Text.truncate(plan.summary(), 160), plan);

        String location = null;
        if (plan.locationRelevant() && hasLocation) {
            location = locationText != null ? locationText : task.get("lat") + "," + task.get("lon");
            db.update("UPDATE tasks SET location_relevant=TRUE WHERE id=?", taskId);
            events.emit(taskId, version, "location_used", "coordinator", null, "Task is location-dependent; using the supplied location", null);
        } else if (hasLocation) {
            db.update("UPDATE tasks SET location_relevant=FALSE WHERE id=?", taskId);
            events.emit(taskId, version, "location_ignored", "coordinator", null,
                    "Location supplied but not relevant to this task; it is excluded from analysis and reports", null);
        }
        AgentContext located = location == null ? ctx : rebuild(ctx, location);
        final AgentContext c = located;
        run = location == null ? run : new Run(taskId, uid, version, engine, checkpoints, c);
        final Run r = run;

        for (SpecialistAgent s : team.specialists()) {
            message(r, AgentRole.COORDINATOR, s.role(), plan.subtasks().getOrDefault(s.role().id(), s.role().mission()));
        }
        state(r, AgentRole.COORDINATOR, AgentState.COMPLETED, "Plan issued");
        checkpointIfNeeded(r, "after_plan", "The Coordinator has produced a plan and assumptions. Review before specialists start.",
                Map.of("plan", plan));

        // ---- Stage 2: parallel specialist analysis
        runSpecialists(r, team.specialists());
        checkStop(r);

        // ---- Stage 3: critic review
        state(r, AgentRole.CRITIC, AgentState.REVIEWING, "Reviewing " + c.findings().size() + " findings");
        Reasoned<List<Challenge>> rc = team.critic().review(c, engine);
        noteEngine(r, AgentRole.CRITIC, rc);
        Map<Long, Finding> byId = c.findings().stream().collect(Collectors.toMap(Finding::id, f -> f));
        for (Challenge ch : rc.value()) {
            Finding f = byId.get(ch.findingId());
            if (f == null) continue;
            long cid = db.insert("conflicts", Db.row("task_id", taskId, "version_no", version, "raised_by", AgentRole.CRITIC.id(),
                    "target_agent", f.agent().id(), "finding_id", f.id(), "objection", ch.objection(), "severity", ch.severity(), "status", "open"));
            events.emit(taskId, version, "conflict", AgentRole.CRITIC.id(), f.agent().id(),
                    "Conflict (" + ch.severity() + ") on \"" + Text.truncate(f.title(), 70) + "\": " + Text.truncate(ch.objection(), 140),
                    Map.of("conflictId", cid, "findingId", f.id(), "severity", ch.severity()));
        }
        reload(c);
        state(r, AgentRole.CRITIC, rc.value().isEmpty() ? AgentState.COMPLETED : AgentState.WAITING,
                rc.value().isEmpty() ? "No objections" : rc.value().size() + " objection(s) raised");

        // ---- Stage 4: debate
        debate(r);
        state(r, AgentRole.CRITIC, AgentState.COMPLETED, "Debate round closed");
        checkpointIfNeeded(r, "after_debate", "The debate round is complete. Review findings and conflicts.", Map.of());

        List<Conflict> unresolved = c.conflicts().stream().filter(x -> "unresolved".equals(x.status())).toList();
        if (!unresolved.isEmpty() && checkpoints.contains("on_conflict")) {
            List<Map<String, Object>> payload = unresolved.stream().map(x -> {
                Finding f = c.findings().stream().filter(ff -> ff.id() == x.findingId()).findFirst().orElse(null);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.id());
                m.put("findingId", x.findingId());
                m.put("findingTitle", f == null ? "" : f.title());
                m.put("agent", x.target().id());
                m.put("objection", x.objection());
                m.put("severity", x.severity());
                return m;
            }).toList();
            Decision d = checkpoint(r, "on_conflict", unresolved.size() + " conflict(s) could not be resolved by the agents. Decide each one, "
                    + "optionally add evidence, then resume.", Map.of("conflicts", payload));
            applyConflictChoices(r, d);
        }
        refreshEvidenceIfChanged(r);
        checkpointIfNeeded(r, "before_synthesis", "All analysis is complete. Approve to let the Synthesizer produce the recommendation.", Map.of());

        // ---- Stage 5: synthesis
        state(r, AgentRole.SYNTHESIZER, AgentState.PROCESSING, "Weighing the debate");
        for (SpecialistAgent s : team.specialists()) message(r, s.role(), AgentRole.SYNTHESIZER, "Final findings submitted");
        Reasoned<Synthesis> rs = team.synthesizer().synthesize(c, engine);
        noteEngine(r, AgentRole.SYNTHESIZER, rs);
        Synthesis syn = rs.value();

        ObjectNode result = Json.obj();
        result.put("engine", engine);
        result.set("plan", Json.MAPPER.valueToTree(plan));
        result.set("synthesis", Json.MAPPER.valueToTree(syn));
        ArrayNode notes = result.putArray("engineNotes");
        r.engineNotes.forEach(notes::add);
        if (location != null) result.set("geo", geoResults(r, task, location, syn, plan));

        db.update("UPDATE versions SET result_json=?, recommendation=?, confidence=?, status='completed' WHERE task_id=? AND version_no=?",
                Json.write(result), syn.recommendation(), syn.confidence(), taskId, version);
        events.emit(taskId, version, "recommendation", AgentRole.SYNTHESIZER.id(), null,
                "Recommendation: " + Text.truncate(syn.recommendation(), 200), Map.of("confidence", syn.confidence()));
        state(r, AgentRole.SYNTHESIZER, AgentState.COMPLETED, "Recommendation ready");
        for (AgentRole role : AgentRole.values()) if (r.states.get(role) != AgentState.COMPLETED) state(r, role, AgentState.COMPLETED, null);

        storeMemories(r, syn);
        tasks.setStatus(taskId, "completed");
        events.emit(taskId, version, "status", null, null, "Analysis v" + version + " completed", Map.of("status", "completed"));
        long open = c.conflicts().stream().filter(x -> "unresolved".equals(x.status())).count();
        events.notifyUser(uid, taskId, "completed", "Analysis completed: " + Text.truncate(title, 80)
                + (open > 0 ? " (" + open + " unresolved conflict(s) noted)" : ""));
    }

    private AgentContext rebuild(AgentContext ctx, String location) {
        AgentContext n = new AgentContext(ctx.taskId(), ctx.version(), ctx.title(), ctx.description(), location,
                ctx.assumptions(), ctx.evidence(), ctx.memories());
        n.setPlan(ctx.plan());
        n.setScenario(ctx.scenario());
        n.setIntensity(ctx.intensity());
        return n;
    }

    private void runSpecialists(Run r, List<SpecialistAgent> agents) {
        List<Future<?>> futures = new ArrayList<>();
        for (SpecialistAgent s : agents) {
            futures.add(pool.submit(() -> {
                state(r, s.role(), AgentState.PROCESSING, "Analysing");
                Reasoned<List<FindingDraft>> res = s.analyze(r.ctx, r.engine);
                noteEngine(r, s.role(), res);
                for (FindingDraft d : res.value()) {
                    long fid = db.insert("findings", Db.row("task_id", r.taskId, "version_no", r.version, "agent", s.role().id(),
                            "kind", d.kind(), "title", d.title(), "detail", d.detail(), "original_detail", d.detail(),
                            "confidence", d.confidence(), "status", "active",
                            "evidence_refs", d.evidenceRefs().stream().map(String::valueOf).collect(Collectors.joining(","))));
                    events.emit(r.taskId, r.version, "finding", s.role().id(), null, Text.truncate(d.title(), 160),
                            Map.of("findingId", fid, "kind", d.kind(), "confidence", d.confidence()));
                }
                message(r, s.role(), AgentRole.CRITIC, res.value().size() + " finding(s) ready for review");
                state(r, s.role(), AgentState.WAITING, "Awaiting review");
                return null;
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("Specialist failed: " + e.getCause().getMessage(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StopRun("interrupted");
            }
        }
        reload(r.ctx);
    }

    private void debate(Run r) {
        Map<AgentRole, List<Conflict>> byAgent = r.ctx.conflicts().stream().filter(x -> "open".equals(x.status()))
                .collect(Collectors.groupingBy(Conflict::target, LinkedHashMap::new, Collectors.toList()));
        for (AgentRole role : AgentRole.values()) if (!byAgent.containsKey(role) && r.states.get(role) == AgentState.WAITING && role != AgentRole.CRITIC)
            state(r, role, AgentState.COMPLETED, "No objections to address");
        for (Map.Entry<AgentRole, List<Conflict>> e : byAgent.entrySet()) {
            SpecialistAgent agent = team.specialist(e.getKey());
            state(r, agent.role(), AgentState.REVIEWING, "Responding to " + e.getValue().size() + " objection(s)");
            Reasoned<List<Revision>> rev = agent.respond(r.ctx, e.getValue(), r.engine);
            noteEngine(r, agent.role(), rev);
            Map<Long, Conflict> mine = e.getValue().stream().collect(Collectors.toMap(Conflict::id, x -> x));
            Set<Long> answered = new HashSet<>();
            for (Revision v : rev.value()) {
                Conflict cf = mine.get(v.conflictId());
                if (cf == null || !answered.add(cf.id())) continue;
                applyRevision(r, agent.role(), cf, v);
            }
            for (Conflict cf : e.getValue()) if (!answered.contains(cf.id())) {
                db.update("UPDATE conflicts SET status='unresolved', response_action='none', resolution=? WHERE id=?",
                        "The agent did not respond to this objection.", cf.id());
                events.emit(r.taskId, r.version, "conflict_unresolved", agent.role().id(), AgentRole.CRITIC.id(),
                        "No response to conflict #" + cf.id(), Map.of("conflictId", cf.id()));
            }
            state(r, agent.role(), AgentState.COMPLETED, "Debate round complete");
        }
        reload(r.ctx);
    }

    private void applyRevision(Run r, AgentRole agent, Conflict cf, Revision v) {
        switch (v.action()) {
            case "revise" -> {
                String detail = v.revisedDetail() == null || v.revisedDetail().isBlank() ? null : v.revisedDetail();
                db.update("UPDATE findings SET detail=COALESCE(?, detail), confidence=?, status='revised' WHERE id=?",
                        detail, v.revisedConfidence(), cf.findingId());
                db.update("UPDATE conflicts SET status='resolved', response_action='revise', resolution=? WHERE id=?", v.rationale(), cf.id());
                events.emit(r.taskId, r.version, "revision", agent.id(), AgentRole.CRITIC.id(),
                        "Revised finding #" + cf.findingId() + ": " + Text.truncate(v.rationale(), 150),
                        Map.of("conflictId", cf.id(), "findingId", cf.findingId(), "confidence", v.revisedConfidence()));
            }
            case "withdraw" -> {
                db.update("UPDATE findings SET status='withdrawn' WHERE id=?", cf.findingId());
                db.update("UPDATE conflicts SET status='resolved', response_action='withdraw', resolution=? WHERE id=?", v.rationale(), cf.id());
                events.emit(r.taskId, r.version, "revision", agent.id(), AgentRole.CRITIC.id(),
                        "Withdrew finding #" + cf.findingId() + ": " + Text.truncate(v.rationale(), 150),
                        Map.of("conflictId", cf.id(), "findingId", cf.findingId(), "withdrawn", true));
            }
            default -> {
                boolean stands = !"high".equals(cf.severity());
                db.update("UPDATE conflicts SET status=?, response_action='defend', resolution=? WHERE id=?",
                        stands ? "resolved" : "unresolved", v.rationale(), cf.id());
                events.emit(r.taskId, r.version, stands ? "defense" : "conflict_unresolved", agent.id(), AgentRole.CRITIC.id(),
                        (stands ? "Defended finding #" : "Deadlock on finding #") + cf.findingId() + ": " + Text.truncate(v.rationale(), 150),
                        Map.of("conflictId", cf.id(), "findingId", cf.findingId()));
            }
        }
        message(r, agent, AgentRole.CRITIC, v.action() + ": " + Text.truncate(v.rationale(), 100));
    }

    private void applyConflictChoices(Run r, Decision d) {
        if (d.conflictChoices() == null) return;
        for (Map.Entry<Long, String> e : d.conflictChoices().entrySet()) {
            Optional<Map<String, Object>> row = db.one("SELECT * FROM conflicts WHERE id=? AND task_id=? AND version_no=?", e.getKey(), r.taskId, r.version);
            if (row.isEmpty()) continue;
            long fid = Db.lng(row.get(), "finding_id");
            if ("reject".equals(e.getValue())) {
                db.update("UPDATE findings SET status='rejected' WHERE id=?", fid);
                db.update("UPDATE conflicts SET status='user_rejected', resolution=COALESCE(resolution,'') || ' | User accepted the objection and rejected the finding.' WHERE id=?", e.getKey());
            } else {
                db.update("UPDATE findings SET status='approved' WHERE id=?", fid);
                db.update("UPDATE conflicts SET status='user_upheld', resolution=COALESCE(resolution,'') || ' | User upheld the finding.' WHERE id=?", e.getKey());
            }
            events.emit(r.taskId, r.version, "user_resolution", "user", Db.str(row.get(), "target_agent"),
                    ("reject".equals(e.getValue()) ? "User rejected finding #" : "User upheld finding #") + fid, Map.of("conflictId", e.getKey(), "findingId", fid));
        }
        reload(r.ctx);
    }

    /** If the user added or rejected evidence while paused, re-run the Evidence Inspector on the new set. */
    private void refreshEvidenceIfChanged(Run r) {
        List<EvidenceItem> now = evidence.activeItems(r.taskId);
        Set<Long> before = r.ctx.evidence().stream().map(EvidenceItem::id).collect(Collectors.toSet());
        Set<Long> after = now.stream().map(EvidenceItem::id).collect(Collectors.toSet());
        if (before.equals(after)) return;
        r.ctx.replaceEvidence(now);
        db.update("UPDATE findings SET status='superseded' WHERE task_id=? AND version_no=? AND agent='inspector' AND status IN ('active','revised')", r.taskId, r.version);
        events.emit(r.taskId, r.version, "evidence_refresh", "inspector", null,
                "Evidence changed during review (" + before.size() + " → " + after.size() + " items); re-inspecting", null);
        runSpecialists(r, List.of(team.specialist(AgentRole.INSPECTOR)));
        state(r, AgentRole.INSPECTOR, AgentState.COMPLETED, "Re-inspection complete");
    }

    private ObjectNode geoResults(Run r, Map<String, Object> task, String location, Synthesis syn, Plan plan) {
        ObjectNode g = Json.obj();
        Double lat = task.get("lat") == null ? null : ((Number) task.get("lat")).doubleValue();
        Double lon = task.get("lon") == null ? null : ((Number) task.get("lon")).doubleValue();
        if ((lat == null || lon == null) && geo.enabled()) {
            Optional<GeoService.Place> p = geo.geocode(location);
            if (p.isPresent()) {
                lat = p.get().lat();
                lon = p.get().lon();
                db.update("UPDATE tasks SET lat=?, lon=? WHERE id=?", lat, lon, r.taskId);
            }
        }
        g.put("location", location);
        g.put("available", geo.enabled() && lat != null);
        if (lat == null || lon == null) {
            g.put("note", geo.enabled() ? "The location could not be geocoded." : "Geocoding is disabled.");
            return g;
        }
        g.put("lat", lat);
        g.put("lon", lon);
        ArrayNode places = g.putArray("places");
        LinkedHashSet<String> queries = new LinkedHashSet<>(syn.geoQueries());
        queries.addAll(plan.geoQueries());
        int n = 0;
        for (String q : queries) {
            if (n++ >= 3) break;
            for (GeoService.Place p : geo.nearby(q, lat, lon)) {
                ObjectNode o = places.addObject();
                o.put("query", q);
                o.put("name", p.name());
                o.put("lat", p.lat());
                o.put("lon", p.lon());
                o.put("category", p.category());
            }
        }
        events.emit(r.taskId, r.version, "geo_results", AgentRole.SYNTHESIZER.id(), null,
                places.size() + " place(s) found near the supplied location", Map.of("queries", queries));
        return g;
    }

    private void storeMemories(Run r, Synthesis syn) {
        String ctxMemory = "Task \"" + r.ctx.title() + "\" — recommendation: " + syn.recommendation();
        if (!memory.exists(r.uid, ctxMemory)) {
            Map<String, Object> m = memory.save(r.uid, r.taskId, "recommendation", ctxMemory, null, "auto: final recommendation v" + r.version);
            events.emit(r.taskId, r.version, "memory_stored", "synthesizer", null, "Stored recommendation in memory vault", Map.of("memoryId", m.get("id")));
        }
        Set<Long> contested = r.ctx.conflicts().stream().filter(x -> "unresolved".equals(x.status()) || "user_rejected".equals(x.status()))
                .map(Conflict::findingId).collect(Collectors.toSet());
        int stored = 0;
        for (Finding f : r.ctx.activeFindings()) {
            if (stored >= 4) break;
            boolean approved = "approved".equals(f.status()) || (f.confidence() >= 0.75 && !contested.contains(f.id()) && !"superseded".equals(f.status()));
            if (!approved || "gap".equals(f.kind())) continue;
            String content = f.title() + ": " + f.detail();
            if (memory.exists(r.uid, content)) continue;
            Map<String, Object> m = memory.save(r.uid, r.taskId, f.kind(), content, null,
                    "approved".equals(f.status()) ? "user-approved finding" : "auto: survived debate (confidence " + String.format("%.2f", f.confidence()) + ")");
            events.emit(r.taskId, r.version, "memory_stored", f.agent().id(), null, "Stored finding #" + f.id() + " in memory vault", Map.of("memoryId", m.get("id")));
            stored++;
        }
    }

    // ------------------------------------------------------------------ checkpoints

    private void checkpointIfNeeded(Run r, String name, String message, Map<String, Object> payload) {
        checkStop(r);
        boolean pauseRequested = db.one("SELECT pause_requested FROM tasks WHERE id=?", r.taskId).map(t -> Db.bool(t, "pause_requested")).orElse(false);
        if (r.checkpoints.contains(name)) checkpoint(r, name, message, payload);
        else if (pauseRequested) checkpoint(r, "manual_pause", "Paused at your request (before stage: " + name.replace('_', ' ') + ").", payload);
    }

    private Decision checkpoint(Run r, String name, String message, Map<String, Object> payload) {
        CompletableFuture<Decision> future = new CompletableFuture<>();
        gates.put(r.taskId, new Gate(name, r.version, message, payload, future));
        db.update("UPDATE tasks SET status='awaiting_approval', pause_requested=FALSE, updated_at=CURRENT_TIMESTAMP WHERE id=?", r.taskId);
        Map<AgentRole, AgentState> saved = new EnumMap<>(AgentRole.class);
        saved.putAll(r.states);
        for (AgentRole role : AgentRole.values())
            if (r.states.get(role) != AgentState.COMPLETED) state(r, role, AgentState.WAITING, "Waiting for human approval");
        events.emit(r.taskId, r.version, "checkpoint", null, "user", message, Map.of("checkpoint", name));
        events.notifyUser(r.uid, r.taskId, "on_conflict".equals(name) ? "conflict" : "checkpoint",
                ("on_conflict".equals(name) ? "Unresolved conflict needs your decision: " : "Analysis paused for approval: ") + Text.truncate(r.ctx.title(), 80));
        events.pushUser(r.uid, "task-status", Map.of("taskId", r.taskId, "status", "awaiting_approval"));
        Decision d;
        try {
            d = future.get();
        } catch (Exception e) {
            throw new StopRun("interrupted while waiting");
        } finally {
            gates.remove(r.taskId);
        }
        db.insert("decisions", Db.row("task_id", r.taskId, "version_no", r.version, "checkpoint", name, "action", d.action(),
                "note", d.note(), "details", Json.write(d.conflictChoices() == null ? Map.of() : d.conflictChoices())));
        events.emit(r.taskId, r.version, "decision", "user", null,
                "User decision at " + name.replace('_', ' ') + ": " + d.action() + (d.note() == null || d.note().isBlank() ? "" : " — " + Text.truncate(d.note(), 160)),
                Map.of("checkpoint", name, "action", d.action(), "choices", d.conflictChoices() == null ? Map.of() : d.conflictChoices()));
        if (d.stop()) throw new StopRun("stopped by user at " + name);
        if (d.note() != null && !d.note().isBlank()) r.ctx.userNotes().add(d.note().trim());
        tasks.setStatus(r.taskId, "running");
        events.pushUser(r.uid, "task-status", Map.of("taskId", r.taskId, "status", "running"));
        for (Map.Entry<AgentRole, AgentState> e : saved.entrySet())
            if (e.getValue() != AgentState.COMPLETED) state(r, e.getKey(), e.getValue(), "Resumed");
        return d;
    }

    private void checkStop(Run r) {
        if (cancelled.contains(r.taskId)) throw new StopRun("stopped by user");
    }

    // ------------------------------------------------------------------ helpers

    private void reload(AgentContext ctx) {
        List<Finding> fs = new ArrayList<>();
        for (Map<String, Object> f : db.list("SELECT * FROM findings WHERE task_id=? AND version_no=? AND status<>'superseded' ORDER BY id", ctx.taskId(), ctx.version())) {
            List<Long> refs = new ArrayList<>();
            for (String s : Objects.toString(f.get("evidence_refs"), "").split(",")) if (!s.isBlank()) refs.add(Long.parseLong(s.trim()));
            fs.add(new Finding(Db.lng(f, "id"), AgentRole.of(Db.str(f, "agent")), Db.str(f, "kind"), Db.str(f, "title"),
                    Db.str(f, "detail"), Db.dbl(f, "confidence", 0.5), Db.str(f, "status"), refs));
        }
        ctx.setFindings(fs);
        List<Conflict> cs = new ArrayList<>();
        for (Map<String, Object> c : db.list("SELECT * FROM conflicts WHERE task_id=? AND version_no=? ORDER BY id", ctx.taskId(), ctx.version()))
            cs.add(new Conflict(Db.lng(c, "id"), Db.lng(c, "finding_id"), AgentRole.of(Db.str(c, "target_agent")),
                    Db.str(c, "objection"), Db.str(c, "severity"), Db.str(c, "status")));
        ctx.setConflicts(cs);
    }

    private void state(Run r, AgentRole role, AgentState s, String summary) {
        r.states.put(role, s);
        events.emit(r.taskId, r.version, "agent_state", role.id(), null, summary == null ? role.label() + " is " + s.id() : summary,
                Map.of("state", s.id()));
    }

    private void message(Run r, AgentRole from, AgentRole to, String text) {
        events.emit(r.taskId, r.version, "message", from.id(), to.id(), text, null);
    }

    private void noteEngine(Run r, AgentRole role, Reasoned<?> res) {
        if (res.note() != null) {
            r.engineNotes.add(role.label() + ": " + res.note());
            events.emit(r.taskId, r.version, "engine_fallback", role.id(), null, role.label() + " " + res.note(), Map.of("engine", res.engine()));
        }
    }
}
