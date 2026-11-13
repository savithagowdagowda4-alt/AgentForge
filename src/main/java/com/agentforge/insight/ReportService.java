package com.agentforge.insight;

import com.agentforge.agent.AgentRole;
import com.agentforge.common.Db;
import com.agentforge.task.TaskQueryService;
import com.agentforge.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Downloadable reports (Markdown / HTML). Location is included only when the task was
 * judged location-dependent; otherwise it never appears in a report.
 */
@Service
public class ReportService {
    private final TaskService tasks;
    private final TaskQueryService query;
    private final Db db;

    public ReportService(TaskService tasks, TaskQueryService query, Db db) {
        this.tasks = tasks;
        this.query = query;
        this.db = db;
    }

    @SuppressWarnings("unchecked")
    public String markdown(long taskId, long uid, int version) {
        Map<String, Object> t = tasks.owned(taskId, uid);
        Map<String, Object> v = query.version(taskId, uid, version);
        JsonNode result = (JsonNode) v.get("result");
        JsonNode syn = result.path("synthesis");
        StringBuilder md = new StringBuilder();
        md.append("# ").append(t.get("title")).append("\n\n");
        md.append("*AgentForge report — version ").append(version).append(" (").append(v.get("change_note")).append("), generated ")
                .append(new Date()).append(". Engine: ").append(engineLabel(result.path("engine").asText(Objects.toString(t.get("engine"), "")))).append(".*\n\n");
        md.append("## Task\n\n").append(t.get("description")).append("\n\n");
        if (Db.bool(t, "location_relevant") && t.get("location_text") != null)
            md.append("**Location (used because the task is location-dependent):** ").append(t.get("location_text")).append("\n\n");

        md.append("## Recommendation\n\n");
        if (syn.isMissingNode()) md.append("_No recommendation: this version did not complete (status: ").append(v.get("status")).append(")._\n\n");
        else {
            md.append(syn.path("recommendation").asText()).append("\n\n");
            md.append(String.format("**Confidence:** %.0f%%%n%n", syn.path("confidence").asDouble() * 100));
            md.append("### Reasoning\n\n");
            syn.path("reasoning").forEach(r -> md.append("1. ").append(r.asText()).append("\n"));
            if (syn.path("openQuestions").size() > 0) {
                md.append("\n### Open questions\n\n");
                syn.path("openQuestions").forEach(q -> md.append("- ").append(q.asText()).append("\n"));
            }
            md.append("\n");
        }
        md.append("## Assumptions\n\n");
        List<String> as = TaskQueryService.stringList(v.get("assumptions"));
        for (int i = 0; i < as.size(); i++) md.append("- **A").append(i + 1).append("** ").append(as.get(i)).append("\n");
        md.append("\n## Findings by agent\n\n");
        List<Map<String, Object>> findings = (List<Map<String, Object>>) v.get("findings");
        for (AgentRole r : List.of(AgentRole.ANALYST, AgentRole.INSPECTOR, AgentRole.RISK)) {
            md.append("### ").append(r.label()).append("\n\n");
            boolean any = false;
            for (Map<String, Object> f : findings) {
                if (!r.id().equals(f.get("agent")) || "superseded".equals(f.get("status"))) continue;
                any = true;
                md.append(String.format("- **#%s %s** _(%s, confidence %.0f%%, %s)_ — %s%n", f.get("id"), f.get("title"), f.get("kind"),
                        Db.dbl(f, "confidence", 0) * 100, f.get("status"), Objects.toString(f.get("detail"), "").replace("\n", " ")));
            }
            if (!any) md.append("_No findings._\n");
            md.append("\n");
        }
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) v.get("conflicts");
        md.append("## Debate and conflicts\n\n");
        if (conflicts.isEmpty()) md.append("_The Devil's Advocate raised no objections._\n\n");
        for (Map<String, Object> c : conflicts)
            md.append(String.format("- **Conflict #%s** (%s) on finding #%s \"%s\": %s → **%s**. %s%n", c.get("id"), c.get("severity"),
                    c.get("finding_id"), c.get("finding_title"), c.get("objection"), c.get("status"), Objects.toString(c.get("resolution"), "")));
        md.append("\n## Evidence\n\n");
        List<Map<String, Object>> ev = db.list("SELECT id, filename, extraction_method, status FROM evidence WHERE task_id=? ORDER BY id", taskId);
        if (ev.isEmpty()) md.append("_No evidence uploaded._\n");
        for (Map<String, Object> e : ev)
            md.append("- #").append(e.get("id")).append(" ").append(e.get("filename")).append(" — ").append(e.get("extraction_method"))
                    .append(", ").append(e.get("status")).append("\n");
        List<Map<String, Object>> decisions = db.list("SELECT * FROM decisions WHERE task_id=? AND version_no=? ORDER BY id", taskId, version);
        if (!decisions.isEmpty()) {
            md.append("\n## Human decisions\n\n");
            for (Map<String, Object> d : decisions)
                md.append("- ").append(d.get("created_at")).append(" at **").append(d.get("checkpoint")).append("**: ").append(d.get("action"))
                        .append(d.get("note") == null ? "" : " — " + d.get("note")).append("\n");
        }
        List<Map<String, Object>> mem = (List<Map<String, Object>>) v.get("memoryUses");
        if (!mem.isEmpty()) {
            md.append("\n## Reused memory\n\n");
            for (Map<String, Object> m : mem) md.append("- Memory #").append(m.get("memory_id")).append(": ").append(m.get("snapshot")).append("\n");
        }
        if (result.path("engineNotes").size() > 0) {
            md.append("\n## Engine notes\n\n");
            result.path("engineNotes").forEach(n -> md.append("- ").append(n.asText()).append("\n"));
        }
        return md.toString();
    }

    public String html(long taskId, long uid, int version) {
        String md = markdown(taskId, uid, version);
        StringBuilder body = new StringBuilder();
        boolean inList = false, inOl = false;
        for (String line : md.split("\n")) {
            String l = esc(line);
            boolean li = l.startsWith("- "), ol = l.startsWith("1. ");
            if (inList && !li) { body.append("</ul>"); inList = false; }
            if (inOl && !ol) { body.append("</ol>"); inOl = false; }
            if (l.startsWith("### ")) body.append("<h3>").append(inline(l.substring(4))).append("</h3>");
            else if (l.startsWith("## ")) body.append("<h2>").append(inline(l.substring(3))).append("</h2>");
            else if (l.startsWith("# ")) body.append("<h1>").append(inline(l.substring(2))).append("</h1>");
            else if (li) { if (!inList) { body.append("<ul>"); inList = true; } body.append("<li>").append(inline(l.substring(2))).append("</li>"); }
            else if (ol) { if (!inOl) { body.append("<ol>"); inOl = true; } body.append("<li>").append(inline(l.substring(3))).append("</li>"); }
            else if (!l.isBlank()) body.append("<p>").append(inline(l)).append("</p>");
        }
        if (inList) body.append("</ul>");
        if (inOl) body.append("</ol>");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>AgentForge report</title><style>"
                + "body{font:15px/1.6 system-ui,Segoe UI,sans-serif;max-width:860px;margin:40px auto;padding:0 20px;color:#1b1f2a}"
                + "h1{font-size:26px;border-bottom:2px solid #4f46e5;padding-bottom:8px}h2{margin-top:32px;color:#3730a3}"
                + "em{color:#555}li{margin:4px 0}@media print{body{margin:0}}</style></head><body>" + body + "</body></html>";
    }

    private static String engineLabel(String e) {
        return "claude".equals(e) ? "Claude AI" : "rules".equals(e) ? "Rule-based (no AI service configured)" : e;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String inline(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>").replaceAll("(?<![*\\w])_(.+?)_(?![\\w])", "<em>$1</em>")
                .replaceAll("\\*(.+?)\\*", "<em>$1</em>");
    }
}
