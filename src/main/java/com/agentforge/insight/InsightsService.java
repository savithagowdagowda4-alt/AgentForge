package com.agentforge.insight;

import com.agentforge.agent.AgentRole;
import com.agentforge.common.Db;
import org.springframework.stereotype.Service;

import java.util.*;

/** Aggregated analytics across all of a user's analyses (agent performance, debate outcomes, confidence trend). */
@Service
public class InsightsService {
    private final Db db;

    public InsightsService(Db db) {
        this.db = db;
    }

    public Map<String, Object> insights(long uid) {
        Map<String, Object> out = new LinkedHashMap<>();

        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentRole r : AgentRole.values()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", r.id());
            a.put("label", r.label());
            switch (r) {
                case ANALYST, INSPECTOR, RISK -> {
                    Map<String, Object> f = db.one("SELECT COUNT(*) AS n, AVG(f.confidence) AS conf, "
                            + "SUM(CASE WHEN f.status='revised' THEN 1 ELSE 0 END) AS revised, "
                            + "SUM(CASE WHEN f.status='withdrawn' THEN 1 ELSE 0 END) AS withdrawn, "
                            + "SUM(CASE WHEN f.status='rejected' THEN 1 ELSE 0 END) AS rejected, "
                            + "SUM(CASE WHEN f.status='approved' THEN 1 ELSE 0 END) AS approved "
                            + "FROM findings f JOIN tasks t ON t.id=f.task_id WHERE t.user_id=? AND f.agent=? AND f.status<>'superseded'", uid, r.id()).orElseThrow();
                    long challenged = count("SELECT COUNT(*) AS n FROM conflicts c JOIN tasks t ON t.id=c.task_id WHERE t.user_id=? AND c.target_agent=?", uid, r.id());
                    a.put("findings", num(f.get("n")));
                    a.put("avgConfidence", f.get("conf") == null ? null : Math.round(((Number) f.get("conf")).doubleValue() * 100) / 100.0);
                    a.put("challenged", challenged);
                    a.put("revised", num(f.get("revised")));
                    a.put("withdrawn", num(f.get("withdrawn")));
                    a.put("rejected", num(f.get("rejected")));
                    a.put("approved", num(f.get("approved")));
                }
                case CRITIC -> a.put("objections", count("SELECT COUNT(*) AS n FROM conflicts c JOIN tasks t ON t.id=c.task_id WHERE t.user_id=?", uid));
                case COORDINATOR -> a.put("plans", count("SELECT COUNT(*) AS n FROM versions v JOIN tasks t ON t.id=v.task_id WHERE t.user_id=? AND v.plan_json IS NOT NULL", uid));
                case SYNTHESIZER -> a.put("recommendations", count("SELECT COUNT(*) AS n FROM versions v JOIN tasks t ON t.id=v.task_id WHERE t.user_id=? AND v.status='completed'", uid));
            }
            agents.add(a);
        }
        out.put("agents", agents);

        out.put("conflictOutcomes", db.list("SELECT c.status, COUNT(*) AS n FROM conflicts c JOIN tasks t ON t.id=c.task_id WHERE t.user_id=? GROUP BY c.status ORDER BY n DESC", uid));
        out.put("severity", db.list("SELECT c.severity, COUNT(*) AS n FROM conflicts c JOIN tasks t ON t.id=c.task_id WHERE t.user_id=? GROUP BY c.severity", uid));
        out.put("confidenceTrend", db.list("SELECT v.task_id, v.version_no, v.confidence, v.created_at, v.change_note, t.title FROM versions v JOIN tasks t ON t.id=v.task_id "
                + "WHERE t.user_id=? AND v.status='completed' AND v.confidence IS NOT NULL ORDER BY v.created_at, v.id LIMIT 40", uid));
        out.put("engines", db.list("SELECT COALESCE(engine,'not run') AS engine, COUNT(*) AS n FROM tasks WHERE user_id=? GROUP BY engine", uid));
        out.put("evidenceMethods", db.list("SELECT extraction_method AS method, COUNT(*) AS n FROM evidence WHERE user_id=? GROUP BY extraction_method ORDER BY n DESC", uid));
        out.put("decisions", db.list("SELECT d.checkpoint, d.action, COUNT(*) AS n FROM decisions d JOIN tasks t ON t.id=d.task_id WHERE t.user_id=? GROUP BY d.checkpoint, d.action", uid));

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tasks", count("SELECT COUNT(*) AS n FROM tasks WHERE user_id=?", uid));
        totals.put("versions", count("SELECT COUNT(*) AS n FROM versions v JOIN tasks t ON t.id=v.task_id WHERE t.user_id=?", uid));
        totals.put("whatIfs", count("SELECT COUNT(*) AS n FROM versions v JOIN tasks t ON t.id=v.task_id WHERE t.user_id=? AND v.parent_version IS NOT NULL", uid));
        totals.put("events", count("SELECT COUNT(*) AS n FROM events e JOIN tasks t ON t.id=e.task_id WHERE t.user_id=?", uid));
        totals.put("memoryReuses", count("SELECT COUNT(*) AS n FROM memory_uses u JOIN tasks t ON t.id=u.task_id WHERE t.user_id=?", uid));
        totals.put("followups", count("SELECT COUNT(*) AS n FROM chat_messages WHERE user_id=? AND role='user'", uid));
        Map<String, Object> avg = db.one("SELECT AVG(v.confidence) AS c FROM versions v JOIN tasks t ON t.id=v.task_id WHERE t.user_id=? AND v.status='completed'", uid).orElseThrow();
        totals.put("avgConfidence", avg.get("c") == null ? null : Math.round(((Number) avg.get("c")).doubleValue() * 100) / 100.0);
        out.put("totals", totals);
        return out;
    }

    private long count(String sql, Object... args) {
        return db.one(sql, args).map(r -> num(r.get("n"))).orElse(0L);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
