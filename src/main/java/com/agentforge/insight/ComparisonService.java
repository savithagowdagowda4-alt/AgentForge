package com.agentforge.insight;

import com.agentforge.ai.AiService;
import com.agentforge.common.Db;
import com.agentforge.common.Json;
import com.agentforge.common.Text;
import com.agentforge.task.TaskQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;

/** Compares two analysis versions: assumption changes, finding changes and recommendation impact. */
@Service
public class ComparisonService {
    private final TaskQueryService query;
    private final AiService ai;

    public ComparisonService(TaskQueryService query, AiService ai) {
        this.query = query;
        this.ai = ai;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> compare(long taskId, long uid, int a, int b) {
        Map<String, Object> va = query.version(taskId, uid, a);
        Map<String, Object> vb = query.version(taskId, uid, b);
        List<String> asA = TaskQueryService.stringList(va.get("assumptions"));
        List<String> asB = TaskQueryService.stringList(vb.get("assumptions"));

        List<Map<String, Object>> assumptionChanges = new ArrayList<>();
        List<String> changedAssumptionTexts = new ArrayList<>();
        for (String s : asB) if (!asA.contains(s)) {
            String closest = asA.stream().max(Comparator.comparingDouble(x -> Text.similarity(x, s))).orElse(null);
            boolean modified = closest != null && Text.similarity(closest, s) >= 0.5 && !asB.contains(closest);
            assumptionChanges.add(Db.row("type", modified ? "modified" : "added", "before", modified ? closest : null, "after", s));
            changedAssumptionTexts.add(s);
            if (modified) changedAssumptionTexts.add(closest);
        }
        for (String s : asA) if (!asB.contains(s) && assumptionChanges.stream().noneMatch(c -> s.equals(c.get("before")))) {
            assumptionChanges.add(Db.row("type", "removed", "before", s, "after", null));
            changedAssumptionTexts.add(s);
        }

        List<Map<String, Object>> fa = live((List<Map<String, Object>>) va.get("findings"));
        List<Map<String, Object>> fb = live((List<Map<String, Object>>) vb.get("findings"));
        List<Map<String, Object>> findingChanges = new ArrayList<>();
        Set<Long> matchedA = new HashSet<>();
        for (Map<String, Object> f : fb) {
            Map<String, Object> best = null;
            double bestScore = 0;
            for (Map<String, Object> g : fa) {
                if (matchedA.contains(Db.lng(g, "id")) || !Objects.equals(g.get("agent"), f.get("agent"))) continue;
                double s = Text.similarity(text(g), text(f));
                if (s > bestScore) {
                    bestScore = s;
                    best = g;
                }
            }
            if (best != null && bestScore >= 0.45) {
                matchedA.add(Db.lng(best, "id"));
                double dc = Db.dbl(f, "confidence", 0) - Db.dbl(best, "confidence", 0);
                boolean statusChanged = !Objects.equals(best.get("status"), f.get("status"));
                boolean textChanged = Text.similarity(text(best), text(f)) < 0.85;
                if (Math.abs(dc) >= 0.1 || statusChanged || textChanged) {
                    findingChanges.add(Db.row("type", "changed", "agent", f.get("agent"), "before", slim(best), "after", slim(f),
                            "confidenceDelta", round(dc), "why", why(f, changedAssumptionTexts)));
                }
            } else {
                findingChanges.add(Db.row("type", "added", "agent", f.get("agent"), "after", slim(f), "why", why(f, changedAssumptionTexts)));
            }
        }
        for (Map<String, Object> g : fa) if (!matchedA.contains(Db.lng(g, "id")))
            findingChanges.add(Db.row("type", "removed", "agent", g.get("agent"), "before", slim(g), "why", why(g, changedAssumptionTexts)));

        String recA = Objects.toString(va.get("recommendation"), "");
        String recB = Objects.toString(vb.get("recommendation"), "");
        double confA = Db.dbl(va, "confidence", 0), confB = Db.dbl(vb, "confidence", 0);
        boolean recChanged = Text.similarity(recA, recB) < 0.8;

        StringBuilder impact = new StringBuilder();
        impact.append(assumptionChanges.size()).append(" assumption change(s) led to ")
                .append(findingChanges.stream().filter(c -> "changed".equals(c.get("type"))).count()).append(" changed, ")
                .append(findingChanges.stream().filter(c -> "added".equals(c.get("type"))).count()).append(" new and ")
                .append(findingChanges.stream().filter(c -> "removed".equals(c.get("type"))).count()).append(" dropped finding(s). ");
        impact.append(recChanged ? "The recommendation changed. " : "The recommendation is substantially the same. ");
        impact.append(String.format("Confidence moved from %.2f to %.2f (%+.2f).", confA, confB, confB - confA));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", Db.row("version", a, "recommendation", recA, "confidence", confA, "note", va.get("change_note")));
        out.put("b", Db.row("version", b, "recommendation", recB, "confidence", confB, "note", vb.get("change_note")));
        out.put("assumptionChanges", assumptionChanges);
        out.put("findingChanges", findingChanges);
        out.put("recommendationChanged", recChanged);
        out.put("confidenceDelta", round(confB - confA));
        out.put("impact", impact.toString());
        out.put("aiExplanationAvailable", ai.available());
        return out;
    }

    /** Optional model-written explanation of the comparison. */
    public Map<String, Object> explain(long taskId, long uid, int a, int b) {
        Map<String, Object> cmp = compare(taskId, uid, a, b);
        if (!ai.available()) return Map.of("available", false, "message", "AI explanation unavailable: ANTHROPIC_API_KEY is not set.");
        String raw = ai.complete("You explain how changed assumptions altered a multi-agent analysis. Be concrete and brief; do not invent facts.",
                "Comparison data:\n" + Json.write(cmp) + "\n\nReturn JSON only: {\"impact\": \"3-5 sentence explanation of what changed and why\", "
                        + "\"drivers\": [{\"assumption\": \"...\", \"effect\": \"...\"}]}", 4000);
        JsonNode j = Json.extractObject(raw);
        return Map.of("available", true, "impact", Json.text(j, "impact", ""), "drivers", j.path("drivers"));
    }

    private static List<Map<String, Object>> live(List<Map<String, Object>> fs) {
        return fs.stream().filter(f -> !"superseded".equals(f.get("status"))).toList();
    }

    private static String text(Map<String, Object> f) {
        return Objects.toString(f.get("title"), "") + " " + Objects.toString(f.get("detail"), "");
    }

    private static Map<String, Object> slim(Map<String, Object> f) {
        return Db.row("id", f.get("id"), "title", f.get("title"), "detail", f.get("detail"), "confidence", f.get("confidence"),
                "status", f.get("status"), "kind", f.get("kind"));
    }

    private static String why(Map<String, Object> f, List<String> changedAssumptions) {
        String best = null;
        double score = 0;
        for (String a : changedAssumptions) {
            double s = Text.similarity(text(f), a);
            if (s > score) {
                score = s;
                best = a;
            }
        }
        return best != null && score >= 0.2 ? "Linked to changed assumption: \"" + Text.truncate(best, 120) + "\""
                : "No direct link to a changed assumption; difference comes from the re-run of the analysis.";
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
