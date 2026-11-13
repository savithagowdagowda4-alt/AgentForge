package com.agentforge.task;

import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.common.Text;
import org.springframework.stereotype.Service;

import java.util.*;

/** Task persistence with per-user isolation: every lookup is scoped to the owner. */
@Service
public class TaskService {
    private final Db db;

    public TaskService(Db db) {
        this.db = db;
    }

    public Map<String, Object> create(long uid, String title, String description, String inputMode, List<String> checkpoints,
                                      String locationText, Double lat, Double lon) {
        if (description == null || description.isBlank()) throw ApiException.bad("Describe the problem to analyse");
        if (description.length() > 20000) throw ApiException.bad("Description is too long (max 20,000 characters)");
        String t = title == null || title.isBlank() ? Text.truncate(Text.sentences(description).stream().findFirst().orElse(description), 90) : title.trim();
        long id = db.insert("tasks", Db.row(
                "user_id", uid, "title", Text.truncate(t, 200), "description", description.trim(), "status", "draft",
                "input_mode", "voice".equals(inputMode) ? "voice" : "text",
                "checkpoints", String.join(",", sanitizeCheckpoints(checkpoints)),
                "location_text", blankToNull(locationText), "lat", lat, "lon", lon));
        return owned(id, uid);
    }

    public Map<String, Object> updateDraft(long id, long uid, Map<String, Object> body) {
        Map<String, Object> t = owned(id, uid);
        String status = Db.str(t, "status");
        if (!"draft".equals(status) && !"completed".equals(status) && !"failed".equals(status) && !"interrupted".equals(status))
            throw ApiException.conflict("Task cannot be edited while it is " + status);
        if (body.containsKey("title")) db.update("UPDATE tasks SET title=? WHERE id=?", Text.truncate(String.valueOf(body.get("title")), 200), id);
        if (body.containsKey("description")) db.update("UPDATE tasks SET description=? WHERE id=?", String.valueOf(body.get("description")), id);
        if (body.containsKey("checkpoints") && body.get("checkpoints") instanceof List<?> l)
            db.update("UPDATE tasks SET checkpoints=? WHERE id=?", String.join(",", sanitizeCheckpoints(l.stream().map(String::valueOf).toList())), id);
        if (body.containsKey("locationText")) {
            db.update("UPDATE tasks SET location_text=?, lat=?, lon=? WHERE id=?", blankToNull((String) body.get("locationText")),
                    toDouble(body.get("lat")), toDouble(body.get("lon")), id);
        }
        if (body.containsKey("debateIntensity")) setIntensity(id, String.valueOf(body.get("debateIntensity")));
        db.update("UPDATE tasks SET updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        return owned(id, uid);
    }

    /** gentle | balanced | ruthless — how hard the Devil's Advocate pushes back. */
    public void setIntensity(long id, String intensity) {
        String v = Set.of("gentle", "balanced", "ruthless").contains(intensity) ? intensity : "balanced";
        db.update("UPDATE tasks SET debate_intensity=? WHERE id=?", v, id);
    }

    public void setTemplate(long id, String templateId) {
        db.update("UPDATE tasks SET template_id=? WHERE id=?", templateId, id);
    }

    public Map<String, Object> owned(long id, long uid) {
        return db.one("SELECT * FROM tasks WHERE id=? AND user_id=?", id, uid).orElseThrow(() -> ApiException.notFound("Task"));
    }

    public Map<String, Object> get(long id) {
        return db.one("SELECT * FROM tasks WHERE id=?", id).orElseThrow(() -> ApiException.notFound("Task"));
    }

    public List<Map<String, Object>> list(long uid) {
        return db.list("SELECT t.id, t.title, t.status, t.engine, t.current_version, t.created_at, t.updated_at, t.input_mode, t.debate_intensity, "
                + "(SELECT COUNT(*) FROM conflicts c WHERE c.task_id=t.id AND c.version_no=t.current_version AND c.status='unresolved') AS unresolved, "
                + "(SELECT confidence FROM versions v WHERE v.task_id=t.id AND v.version_no=t.current_version) AS confidence "
                + "FROM tasks t WHERE t.user_id=? ORDER BY t.updated_at DESC", uid);
    }

    public void setStatus(long id, String status) {
        db.update("UPDATE tasks SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id);
    }

    public void delete(long id, long uid) {
        owned(id, uid);
        String st = Db.str(get(id), "status");
        if ("running".equals(st) || "awaiting_approval".equals(st)) throw ApiException.conflict("Stop or finish the analysis before deleting it");
        for (String table : List.of("findings", "conflicts", "events", "versions", "memory_uses", "decisions", "evidence", "chat_messages"))
            db.update("DELETE FROM " + table + " WHERE task_id=?", id);
        db.update("UPDATE memories SET task_id=NULL WHERE task_id=?", id);
        db.update("DELETE FROM notifications WHERE task_id=?", id);
        db.update("DELETE FROM tasks WHERE id=?", id);
    }

    public static List<String> sanitizeCheckpoints(List<String> in) {
        Set<String> allowed = Set.of("after_plan", "after_debate", "before_synthesis", "on_conflict");
        List<String> out = new ArrayList<>();
        if (in != null) for (String c : in) if (allowed.contains(c) && !out.contains(c)) out.add(c);
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
