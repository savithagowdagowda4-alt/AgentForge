package com.agentforge.memory;

import com.agentforge.agent.Models.MemoryHit;
import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.common.Text;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The Agent Memory Vault. Stores task context and approved findings per user and retrieves the most
 * relevant ones for new tasks by keyword overlap. Every query is scoped to a single user id.
 */
@Service
public class MemoryService {
    private static final double MIN_SCORE = 0.25;
    private final Db db;

    public MemoryService(Db db) {
        this.db = db;
    }

    public List<MemoryHit> retrieve(long uid, String query, Long excludeTaskId, int limit) {
        List<MemoryHit> hits = new ArrayList<>();
        for (Map<String, Object> m : db.list("SELECT * FROM memories WHERE user_id=?", uid)) {
            Object tid = m.get("task_id");
            if (excludeTaskId != null && tid != null && ((Number) tid).longValue() == excludeTaskId) continue;
            String content = Db.str(m, "content");
            double score = Text.similarity(query, content + " " + Objects.toString(m.get("tags"), ""));
            if (score >= MIN_SCORE) hits.add(new MemoryHit(Db.lng(m, "id"), Db.str(m, "kind"), content, score));
        }
        hits.sort(Comparator.comparingDouble(MemoryHit::score).reversed());
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    public void recordUse(long taskId, int version, MemoryHit hit) {
        db.insert("memory_uses", Db.row("task_id", taskId, "version_no", version, "memory_id", hit.id(), "score", hit.score(), "snapshot", hit.content()));
        db.update("UPDATE memories SET use_count=use_count+1 WHERE id=?", hit.id());
    }

    public List<Map<String, Object>> uses(long taskId, int version) {
        return db.list("SELECT u.memory_id, u.score, u.snapshot, m.kind, m.task_id AS source_task, "
                + "(SELECT title FROM tasks t WHERE t.id=m.task_id) AS source_title, (m.id IS NULL) AS deleted "
                + "FROM memory_uses u LEFT JOIN memories m ON m.id=u.memory_id WHERE u.task_id=? AND u.version_no=? ORDER BY u.score DESC", taskId, version);
    }

    public Map<String, Object> save(long uid, Long taskId, String kind, String content, String tags, String source) {
        if (content == null || content.isBlank()) throw ApiException.bad("Memory content is empty");
        String t = tags == null || tags.isBlank() ? String.join(",", Text.topKeywords(content, 6)) : tags;
        long id = db.insert("memories", Db.row("user_id", uid, "task_id", taskId, "kind", kind, "content", content.trim(), "tags", t, "source", source));
        return get(id, uid);
    }

    public Map<String, Object> get(long id, long uid) {
        return db.one("SELECT m.*, (SELECT title FROM tasks t WHERE t.id=m.task_id) AS task_title FROM memories m WHERE m.id=? AND m.user_id=?", id, uid)
                .orElseThrow(() -> ApiException.notFound("Memory"));
    }

    public List<Map<String, Object>> list(long uid, String q) {
        if (q != null && !q.isBlank()) {
            String like = "%" + q.toLowerCase() + "%";
            return db.list("SELECT m.*, (SELECT title FROM tasks t WHERE t.id=m.task_id) AS task_title FROM memories m WHERE m.user_id=? "
                    + "AND (LOWER(m.content) LIKE ? OR LOWER(m.tags) LIKE ?) ORDER BY m.updated_at DESC", uid, like, like);
        }
        return db.list("SELECT m.*, (SELECT title FROM tasks t WHERE t.id=m.task_id) AS task_title FROM memories m WHERE m.user_id=? ORDER BY m.updated_at DESC", uid);
    }

    public Map<String, Object> update(long id, long uid, String content, String tags) {
        get(id, uid);
        if (content != null && content.isBlank()) throw ApiException.bad("Memory content is empty");
        if (content != null) db.update("UPDATE memories SET content=?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", content.trim(), id, uid);
        if (tags != null) db.update("UPDATE memories SET tags=?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", tags.trim(), id, uid);
        return get(id, uid);
    }

    public void delete(long id, long uid) {
        get(id, uid);
        db.update("DELETE FROM memories WHERE id=? AND user_id=?", id, uid);
    }

    public boolean exists(long uid, String content) {
        return db.one("SELECT id FROM memories WHERE user_id=? AND content=?", uid, content).isPresent();
    }
}
