package com.agentforge.task;

import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.common.Json;
import com.agentforge.evidence.EvidenceService;
import com.agentforge.memory.MemoryService;
import com.agentforge.workflow.Orchestrator;
import org.springframework.stereotype.Service;

import java.util.*;

/** Read-side assembly of task details, shared by the API, comparison, board and reports. */
@Service
public class TaskQueryService {
    private final Db db;
    private final TaskService tasks;
    private final EvidenceService evidence;
    private final MemoryService memory;
    private final Orchestrator orchestrator;

    public TaskQueryService(Db db, TaskService tasks, EvidenceService evidence, MemoryService memory, Orchestrator orchestrator) {
        this.db = db;
        this.tasks = tasks;
        this.evidence = evidence;
        this.memory = memory;
        this.orchestrator = orchestrator;
    }

    public Map<String, Object> detail(long taskId, long uid) {
        Map<String, Object> t = new LinkedHashMap<>(tasks.owned(taskId, uid));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", t);
        out.put("versions", db.list("SELECT version_no, parent_version, change_note, recommendation, confidence, status, created_at "
                + "FROM versions WHERE task_id=? ORDER BY version_no", taskId));
        int cv = ((Number) t.get("current_version")).intValue();
        out.put("current", cv > 0 ? version(taskId, uid, cv) : null);
        out.put("evidence", evidence.list(taskId, uid));
        out.put("decisions", db.list("SELECT * FROM decisions WHERE task_id=? ORDER BY id", taskId));
        orchestrator.gate(taskId).ifPresent(g -> {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("checkpoint", g.checkpoint());
            gate.put("version", g.version());
            gate.put("message", g.message());
            gate.put("payload", g.payload());
            out.put("gate", gate);
        });
        return out;
    }

    public Map<String, Object> version(long taskId, long uid, int v) {
        tasks.owned(taskId, uid);
        Map<String, Object> row = db.one("SELECT * FROM versions WHERE task_id=? AND version_no=?", taskId, v)
                .orElseThrow(() -> ApiException.notFound("Version"));
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("assumptions", Json.read(Db.str(row, "assumptions")));
        out.put("plan", Json.read(Db.str(row, "plan_json")));
        out.put("result", Json.read(Db.str(row, "result_json")));
        out.remove("plan_json");
        out.remove("result_json");
        out.put("findings", findings(taskId, v));
        out.put("conflicts", db.list("SELECT c.*, f.title AS finding_title FROM conflicts c LEFT JOIN findings f ON f.id=c.finding_id "
                + "WHERE c.task_id=? AND c.version_no=? ORDER BY c.id", taskId, v));
        out.put("memoryUses", memory.uses(taskId, v));
        return out;
    }

    public List<Map<String, Object>> findings(long taskId, int v) {
        return db.list("SELECT * FROM findings WHERE task_id=? AND version_no=? ORDER BY id", taskId, v);
    }

    public int currentVersion(long taskId, long uid) {
        return ((Number) tasks.owned(taskId, uid).get("current_version")).intValue();
    }

    public int resolveVersion(long taskId, long uid, Integer requested) {
        int v = requested == null ? currentVersion(taskId, uid) : requested;
        if (v <= 0) throw ApiException.bad("This task has not been analysed yet");
        return v;
    }

    public static List<String> stringList(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof com.fasterxml.jackson.databind.JsonNode n && n.isArray()) n.forEach(x -> out.add(x.asText()));
        return out;
    }
}
