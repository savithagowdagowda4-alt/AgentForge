package com.agentforge.web;

import com.agentforge.auth.CurrentUser;
import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.events.EventService;
import com.agentforge.evidence.EvidenceService;
import com.agentforge.insight.BoardService;
import com.agentforge.insight.ComparisonService;
import com.agentforge.insight.ReportService;
import com.agentforge.memory.MemoryService;
import com.agentforge.task.TaskQueryService;
import com.agentforge.task.TaskService;
import com.agentforge.workflow.Decision;
import com.agentforge.workflow.Orchestrator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final CurrentUser user;
    private final TaskService tasks;
    private final TaskQueryService query;
    private final Orchestrator orchestrator;
    private final EvidenceService evidence;
    private final EventService events;
    private final ComparisonService comparison;
    private final BoardService board;
    private final ReportService reports;
    private final MemoryService memory;
    private final Db db;

    public TaskController(CurrentUser user, TaskService tasks, TaskQueryService query, Orchestrator orchestrator,
                          EvidenceService evidence, EventService events, ComparisonService comparison, BoardService board,
                          ReportService reports, MemoryService memory, Db db) {
        this.user = user;
        this.tasks = tasks;
        this.query = query;
        this.orchestrator = orchestrator;
        this.evidence = evidence;
        this.events = events;
        this.comparison = comparison;
        this.board = board;
        this.reports = reports;
        this.memory = memory;
        this.db = db;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return tasks.list(user.id());
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> t = tasks.create(user.id(), (String) body.get("title"), (String) body.get("description"), (String) body.get("inputMode"),
                body.get("checkpoints") instanceof List<?> l ? (List<String>) l : List.of("on_conflict"),
                (String) body.get("locationText"), num(body.get("lat")), num(body.get("lon")));
        long id = ((Number) t.get("id")).longValue();
        if (body.get("debateIntensity") != null) tasks.setIntensity(id, String.valueOf(body.get("debateIntensity")));
        return tasks.owned(id, user.id());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return query.detail(id, user.id());
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return tasks.updateDraft(id, user.id(), body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        tasks.delete(id, user.id());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> run(@PathVariable long id) {
        return orchestrator.start(id, user.id());
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable long id) {
        orchestrator.requestPause(id, user.id());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        orchestrator.cancel(id, user.id());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/decision")
    public Map<String, Object> decide(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Map<Long, String> choices = new HashMap<>();
        if (body.get("conflictChoices") instanceof Map<?, ?> m)
            m.forEach((k, v) -> choices.put(Long.parseLong(k.toString()), String.valueOf(v)));
        orchestrator.decide(id, user.id(), new Decision(String.valueOf(body.getOrDefault("action", "approve")),
                (String) body.get("note"), choices));
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/whatif")
    @SuppressWarnings("unchecked")
    public Map<String, Object> whatIf(@PathVariable long id, @RequestBody Map<String, Object> body) {
        List<String> assumptions = body.get("assumptions") instanceof List<?> l ? (List<String>) l : List.of();
        return orchestrator.whatIf(id, user.id(), assumptions, (String) body.get("note"));
    }

    @GetMapping("/{id}/versions/{v}")
    public Map<String, Object> version(@PathVariable long id, @PathVariable int v) {
        return query.version(id, user.id(), v);
    }

    @GetMapping("/{id}/compare")
    public Map<String, Object> compare(@PathVariable long id, @RequestParam int a, @RequestParam int b) {
        return comparison.compare(id, user.id(), a, b);
    }

    @PostMapping("/{id}/compare/explain")
    public Map<String, Object> explain(@PathVariable long id, @RequestParam int a, @RequestParam int b) {
        return comparison.explain(id, user.id(), a, b);
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable long id) {
        tasks.owned(id, user.id());
        return events.history(id);
    }

    @GetMapping(path = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id) {
        tasks.owned(id, user.id());
        return events.subscribeTask(id);
    }

    @GetMapping("/{id}/board")
    public Map<String, Object> board(@PathVariable long id, @RequestParam(required = false) Integer version) {
        return board.board(id, user.id(), query.resolveVersion(id, user.id(), version));
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<byte[]> report(@PathVariable long id, @RequestParam(defaultValue = "md") String format,
                                         @RequestParam(required = false) Integer version) {
        long uid = user.id();
        int v = query.resolveVersion(id, uid, version);
        String base = "agentforge-task-" + id + "-v" + v;
        return switch (format) {
            case "html" -> file(reports.html(id, uid, v), base + ".html", "text/html");
            case "json" -> file(com.agentforge.common.Json.write(query.version(id, uid, v)), base + ".json", "application/json");
            case "md" -> file(reports.markdown(id, uid, v), base + ".md", "text/markdown");
            default -> throw ApiException.bad("Unknown format");
        };
    }

    @PostMapping("/{id}/findings/{fid}/memory")
    public Map<String, Object> rememberFinding(@PathVariable long id, @PathVariable long fid) {
        long uid = user.id();
        tasks.owned(id, uid);
        Map<String, Object> f = db.one("SELECT * FROM findings WHERE id=? AND task_id=?", fid, id).orElseThrow(() -> ApiException.notFound("Finding"));
        String content = f.get("title") + ": " + f.get("detail");
        if (memory.exists(uid, content)) throw ApiException.conflict("This finding is already in your memory vault");
        Map<String, Object> m = memory.save(uid, id, Db.str(f, "kind"), content, null, "user-approved finding");
        events.emit(id, ((Number) f.get("version_no")).intValue(), "memory_stored", "user", null, "User saved finding #" + fid + " to memory", Map.of("memoryId", m.get("id")));
        return m;
    }

    @GetMapping("/{id}/evidence")
    public List<Map<String, Object>> evidence(@PathVariable long id) {
        tasks.owned(id, user.id());
        return evidence.list(id, user.id());
    }

    @PostMapping("/{id}/evidence")
    public Map<String, Object> upload(@PathVariable long id, @RequestParam("file") MultipartFile file) throws Exception {
        long uid = user.id();
        Map<String, Object> t = tasks.owned(id, uid);
        String st = Db.str(t, "status");
        if ("running".equals(st)) throw ApiException.conflict("Pause the analysis (or wait for a checkpoint) before adding evidence");
        int cv = ((Number) t.get("current_version")).intValue();
        return evidence.upload(id, uid, file, cv == 0 ? null : cv);
    }

    private static ResponseEntity<byte[]> file(String body, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type + ";charset=UTF-8"))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }
}
