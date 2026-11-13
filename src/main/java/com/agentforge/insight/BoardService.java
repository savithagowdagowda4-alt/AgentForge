package com.agentforge.insight;

import com.agentforge.agent.AgentRole;
import com.agentforge.common.Db;
import com.agentforge.common.Text;
import com.agentforge.task.TaskQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI Visual Thinking Board: turns a version's agent output into a graph of evidence, claims,
 * risks, conflicts and the recommendation, plus the decision tree and the agent workflow.
 */
@Service
public class BoardService {
    private final TaskQueryService query;
    private final Db db;

    public BoardService(TaskQueryService query, Db db) {
        this.query = query;
        this.db = db;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> board(long taskId, long uid, int version) {
        Map<String, Object> v = query.version(taskId, uid, version);
        Map<String, Object> task = db.one("SELECT title FROM tasks WHERE id=?", taskId).orElseThrow();
        JsonNode result = (JsonNode) v.get("result");
        JsonNode syn = result.path("synthesis");

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(node("task", "task", Db.str(task, "title"), Objects.toString(v.get("change_note"), ""), null));

        int i = 1;
        for (String a : TaskQueryService.stringList(v.get("assumptions"))) {
            String id = "a" + i;
            nodes.add(node(id, "assumption", "A" + i++ + ". " + Text.truncate(a, 60), a, null));
            edges.add(edge("task", id, "assumes"));
        }
        Set<String> evidenceIds = new HashSet<>();
        for (Map<String, Object> e : db.list("SELECT id, filename, vision_summary, extraction_method FROM evidence WHERE task_id=? AND status='active'", taskId)) {
            String id = "e" + e.get("id");
            evidenceIds.add(id);
            nodes.add(node(id, "evidence", Db.str(e, "filename"),
                    "Extraction: " + Db.str(e, "extraction_method") + (e.get("vision_summary") != null ? "\n" + e.get("vision_summary") : ""), null));
            edges.add(edge("task", id, "evidence"));
        }
        Set<String> supporting = new HashSet<>();
        syn.path("supportingFindings").forEach(n -> supporting.add("f" + n.asLong()));
        for (Map<String, Object> f : (List<Map<String, Object>>) v.get("findings")) {
            if ("superseded".equals(f.get("status"))) continue;
            String id = "f" + f.get("id");
            String kind = Db.str(f, "kind");
            String type = "risk".equals(kind) ? "risk" : "inconsistency".equals(kind) ? "inconsistency" : "claim";
            Map<String, Object> n = node(id, type, Text.truncate(Db.str(f, "title"), 70), Db.str(f, "detail"), Db.str(f, "agent"));
            n.put("confidence", f.get("confidence"));
            n.put("status", f.get("status"));
            n.put("kind", kind);
            nodes.add(n);
            edges.add(edge(Db.str(f, "agent"), id, "produced"));
            for (String ref : Objects.toString(f.get("evidence_refs"), "").split(","))
                if (!ref.isBlank() && evidenceIds.contains("e" + ref.trim())) edges.add(edge("e" + ref.trim(), id, "supports"));
            if (supporting.contains(id)) edges.add(edge(id, "rec", "supports"));
            else if ("risk".equals(type) && !"rejected".equals(f.get("status")) && !"withdrawn".equals(f.get("status"))) edges.add(edge(id, "rec", "weighs against"));
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) v.get("conflicts")) {
            String id = "c" + c.get("id");
            Map<String, Object> n = node(id, "conflict", "Conflict (" + c.get("severity") + ")", Db.str(c, "objection")
                    + (c.get("resolution") != null ? "\n\nResolution: " + c.get("resolution") : ""), "critic");
            n.put("status", c.get("status"));
            nodes.add(n);
            edges.add(edge(id, "f" + c.get("finding_id"), "challenges"));
        }
        for (AgentRole r : AgentRole.values()) {
            if (r == AgentRole.COORDINATOR || r == AgentRole.SYNTHESIZER || r == AgentRole.CRITIC) continue;
            nodes.add(node(r.id(), "agent", r.label(), r.mission(), r.id()));
            edges.add(edge("task", r.id(), "assigned"));
        }
        if (!syn.isMissingNode()) {
            Map<String, Object> rec = node("rec", "recommendation", "Recommendation", syn.path("recommendation").asText(""), "synthesizer");
            rec.put("confidence", syn.path("confidence").asDouble());
            List<String> reasoning = new ArrayList<>();
            syn.path("reasoning").forEach(n -> reasoning.add(n.asText()));
            rec.put("reasoning", reasoning);
            nodes.add(rec);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("decisionTree", syn.path("decisionTree"));
        out.put("workflow", workflow(taskId, version));
        return out;
    }

    /** Message counts between agents, from the real event log. */
    private Map<String, Object> workflow(long taskId, int version) {
        List<Map<String, Object>> links = db.list("SELECT agent AS source, target, COUNT(*) AS count FROM events "
                + "WHERE task_id=? AND version_no=? AND type IN ('message','conflict','revision','defense','conflict_unresolved') "
                + "AND target IS NOT NULL GROUP BY agent, target", taskId, version);
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentRole r : AgentRole.values()) agents.add(Db.row("id", r.id(), "label", r.label(), "mission", r.mission()));
        return Db.row("agents", agents, "links", links);
    }

    private static Map<String, Object> node(String id, String type, String label, String detail, String agent) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("label", label);
        n.put("detail", detail);
        n.put("agent", agent);
        return n;
    }

    private static Map<String, Object> edge(String from, String to, String label) {
        return Db.row("from", from, "to", to, "label", label);
    }
}
