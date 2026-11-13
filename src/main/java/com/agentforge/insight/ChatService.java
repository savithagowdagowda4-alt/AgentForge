package com.agentforge.insight;

import com.agentforge.ai.AiService;
import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.common.Text;
import com.agentforge.events.EventService;
import com.agentforge.task.TaskQueryService;
import com.agentforge.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Ask the Agents": follow-up questions about a completed analysis, answered strictly from that
 * analysis' findings, evidence and recommendation, with citations. Uses Claude when configured,
 * otherwise a retrieval-based answer built from the most relevant findings.
 */
@Service
public class ChatService {
    private static final Pattern CITE = Pattern.compile("\\[(finding|evidence) (\\d+)]", Pattern.CASE_INSENSITIVE);
    private final Db db;
    private final AiService ai;
    private final TaskService tasks;
    private final TaskQueryService query;
    private final EventService events;

    public ChatService(Db db, AiService ai, TaskService tasks, TaskQueryService query, EventService events) {
        this.db = db;
        this.ai = ai;
        this.tasks = tasks;
        this.query = query;
        this.events = events;
    }

    public List<Map<String, Object>> history(long taskId, long uid) {
        tasks.owned(taskId, uid);
        return db.list("SELECT * FROM chat_messages WHERE task_id=? AND user_id=? ORDER BY id", taskId, uid);
    }

    public void clear(long taskId, long uid) {
        tasks.owned(taskId, uid);
        db.update("DELETE FROM chat_messages WHERE task_id=? AND user_id=?", taskId, uid);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> ask(long taskId, long uid, String question) {
        if (question == null || question.isBlank()) throw ApiException.bad("Ask a question");
        if (question.length() > 2000) throw ApiException.bad("Question is too long (max 2,000 characters)");
        Map<String, Object> task = tasks.owned(taskId, uid);
        int version = ((Number) task.get("current_version")).intValue();
        if (version == 0) throw ApiException.conflict("Run the analysis before asking the agents about it");
        Map<String, Object> v = query.version(taskId, uid, version);
        if (!"completed".equals(v.get("status"))) throw ApiException.conflict("Wait until the analysis has completed");

        List<Map<String, Object>> findings = ((List<Map<String, Object>>) v.get("findings")).stream()
                .filter(f -> !"superseded".equals(f.get("status"))).toList();
        List<Map<String, Object>> evidence = db.list("SELECT id, filename, extracted_text, corrected_text, vision_summary FROM evidence WHERE task_id=? AND status='active'", taskId);
        JsonNode syn = ((JsonNode) v.get("result")).path("synthesis");

        db.insert("chat_messages", Db.row("task_id", taskId, "user_id", uid, "version_no", version, "role", "user", "content", question.trim()));
        String answer;
        String engine;
        if (ai.available()) {
            try {
                answer = ai.complete(systemPrompt(), caseFile(task, v, findings, evidence, syn, recentTurns(taskId, uid)) + "\n\n# Question\n" + question.trim(), 4000).trim();
                engine = "claude";
            } catch (Exception e) {
                answer = retrievalAnswer(question, findings, evidence, syn) + "\n\n(Claude was unavailable: " + e.getMessage() + ". This answer was assembled by rule-based retrieval.)";
                engine = "rules";
            }
        } else {
            answer = retrievalAnswer(question, findings, evidence, syn);
            engine = "rules";
        }
        Set<String> cites = new LinkedHashSet<>();
        Matcher m = CITE.matcher(answer);
        while (m.find()) cites.add(m.group(1).toLowerCase() + ":" + m.group(2));
        long id = db.insert("chat_messages", Db.row("task_id", taskId, "user_id", uid, "version_no", version, "role", "agents",
                "content", answer, "citations", String.join(",", cites), "engine", engine));
        events.emit(taskId, version, "followup", "user", "synthesizer", "Follow-up question: " + Text.truncate(question.trim(), 140), Map.of("messageId", id));
        return db.one("SELECT * FROM chat_messages WHERE id=?", id).orElseThrow();
    }

    private static String systemPrompt() {
        return "You are the AgentForge team (Coordinator, Research Analyst, Evidence Inspector, Risk Assessor, Devil's Advocate and Synthesizer) "
                + "answering a follow-up question about an analysis you already completed. Answer ONLY from the case file. Cite the sources you rely on "
                + "inline exactly like [finding 12] or [evidence 3]. If the case file does not contain the answer, say so plainly and suggest adding evidence "
                + "or running a What-If scenario. Mention which agent's view you are drawing on when it helps. Keep it under 180 words, plain text, no markdown headings.";
    }

    private String caseFile(Map<String, Object> task, Map<String, Object> v, List<Map<String, Object>> findings,
                            List<Map<String, Object>> evidence, JsonNode syn, String turns) {
        StringBuilder sb = new StringBuilder("# Task\n").append(task.get("title")).append("\n").append(task.get("description")).append("\n");
        if (Db.bool(task, "location_relevant") && task.get("location_text") != null) sb.append("Location: ").append(task.get("location_text")).append("\n");
        sb.append("\n# Assumptions\n");
        TaskQueryService.stringList(v.get("assumptions")).forEach(a -> sb.append("- ").append(a).append("\n"));
        sb.append("\n# Recommendation (confidence ").append(String.format("%.2f", syn.path("confidence").asDouble())).append(")\n")
                .append(syn.path("recommendation").asText("")).append("\n");
        syn.path("reasoning").forEach(r -> sb.append("- ").append(r.asText()).append("\n"));
        sb.append("\n# Findings\n");
        for (Map<String, Object> f : findings)
            sb.append("[finding ").append(f.get("id")).append("] (").append(f.get("agent")).append(", ").append(f.get("kind")).append(", ")
                    .append(f.get("status")).append(", confidence ").append(f.get("confidence")).append(") ").append(f.get("title")).append(": ")
                    .append(Text.truncate(Objects.toString(f.get("detail"), ""), 600)).append("\n");
        sb.append("\n# Conflicts\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) v.get("conflicts");
        for (Map<String, Object> c : conflicts)
            sb.append("- on [finding ").append(c.get("finding_id")).append("] (").append(c.get("status")).append("): ").append(c.get("objection"))
                    .append(" → ").append(Objects.toString(c.get("resolution"), "")).append("\n");
        sb.append("\n# Evidence\n");
        for (Map<String, Object> e : evidence) {
            String text = Objects.toString(e.get("corrected_text") != null ? e.get("corrected_text") : e.get("extracted_text"), "");
            sb.append("[evidence ").append(e.get("id")).append("] ").append(e.get("filename")).append(": ").append(Text.truncate(text, 3000));
            if (e.get("vision_summary") != null) sb.append(" | Visual: ").append(Text.truncate(e.get("vision_summary").toString(), 600));
            sb.append("\n");
        }
        if (!turns.isBlank()) sb.append("\n# Earlier conversation\n").append(turns);
        return sb.toString();
    }

    private String recentTurns(long taskId, long uid) {
        List<Map<String, Object>> rows = db.list("SELECT role, content FROM chat_messages WHERE task_id=? AND user_id=? ORDER BY id DESC LIMIT 7", taskId, uid);
        Collections.reverse(rows);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size() - 1; i++) // the last row is the question just stored
            sb.append(rows.get(i).get("role")).append(": ").append(Text.truncate(Objects.toString(rows.get(i).get("content")), 500)).append("\n");
        return sb.toString();
    }

    /** Deterministic answer: the most relevant findings and evidence lines, with citations. */
    static String retrievalAnswer(String question, List<Map<String, Object>> findings, List<Map<String, Object>> evidence, JsonNode syn) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.matches(".*\\b(why|recommend|recommendation|decision|should we|verdict|conclusion)\\b.*")) {
            StringBuilder sb = new StringBuilder("The Synthesizer's recommendation: ").append(syn.path("recommendation").asText("")).append("\n\nReasoning:\n");
            syn.path("reasoning").forEach(r -> sb.append("• ").append(r.asText()).append("\n"));
            return sb.toString().trim();
        }
        List<Map.Entry<Double, String>> scored = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            String text = f.get("title") + " " + Objects.toString(f.get("detail"), "");
            double s = Text.similarity(question, text);
            if (s > 0.15) scored.add(Map.entry(s, "• " + label(Db.str(f, "agent")) + ": " + f.get("title") + " [finding " + f.get("id") + "]"
                    + ("active".equals(f.get("status")) ? "" : " (status: " + f.get("status") + ")")));
        }
        for (Map<String, Object> e : evidence) {
            String text = Objects.toString(e.get("corrected_text") != null ? e.get("corrected_text") : e.get("extracted_text"), "");
            for (String sentence : Text.sentences(text)) {
                double s = Text.similarity(question, sentence);
                if (s > 0.2) scored.add(Map.entry(s * 0.95, "• From " + e.get("filename") + ": \"" + Text.truncate(sentence, 200) + "\" [evidence " + e.get("id") + "]"));
            }
        }
        scored.sort((a, b) -> Double.compare(b.getKey(), a.getKey()));
        if (scored.isEmpty())
            return "The agents' analysis doesn't cover that directly. Their overall recommendation was: " + syn.path("recommendation").asText("")
                    + "\n\nTry rephrasing with words from the task, add evidence that addresses it, or run a What-If scenario.";
        StringBuilder sb = new StringBuilder("Here is what the agents found that relates to your question:\n");
        scored.stream().limit(4).forEach(e -> sb.append(e.getValue()).append("\n"));
        return sb.toString().trim();
    }

    private static String label(String agent) {
        return switch (agent == null ? "" : agent) {
            case "analyst" -> "Research Analyst";
            case "inspector" -> "Evidence Inspector";
            case "risk" -> "Risk Assessor";
            default -> agent;
        };
    }
}
