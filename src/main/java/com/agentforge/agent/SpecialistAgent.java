package com.agentforge.agent;

import com.agentforge.agent.Models.*;
import com.agentforge.ai.AiService;
import com.agentforge.common.Json;
import com.agentforge.common.Text;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An agent that produces findings and defends or revises them when the Devil's Advocate objects.
 * Subclasses supply the analysis focus and the rule-based analysis.
 */
public abstract class SpecialistAgent extends Agent {

    protected SpecialistAgent(AiService ai) {
        super(ai);
    }

    /** What this specialist should look for (appended to the analysis prompt). */
    protected abstract String focus();

    /** Deterministic analysis used when the model is unavailable. */
    protected abstract List<FindingDraft> analyzeWithRules(AgentContext ctx);

    public Reasoned<List<FindingDraft>> analyze(AgentContext ctx, String engine) {
        String assignment = ctx.plan() == null ? "" : ctx.plan().subtasks().getOrDefault(role().id(), "");
        String prompt = ctx.caseFile(false)
                + "\n# Your assignment\n" + (assignment.isBlank() ? focus() : assignment + "\n" + focus())
                + "\n\nReturn JSON: {\"findings\": [{\"kind\": \"" + defaultKind() + "|claim|gap|inconsistency|risk\", "
                + "\"title\": \"short headline\", \"detail\": \"2-4 sentences explaining the finding and why it matters\", "
                + "\"confidence\": 0.0-1.0, \"evidenceRefs\": [evidence ids that support it]}]}. Give 2-5 findings.";
        return reason(engine, prompt, json -> parseFindings(json.get("findings")), () -> analyzeWithRules(ctx));
    }

    protected String defaultKind() {
        return "finding";
    }

    protected List<FindingDraft> parseFindings(JsonNode arr) {
        List<FindingDraft> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) throw new IllegalArgumentException("findings array missing");
        for (JsonNode n : arr) {
            String title = Json.text(n, "title", "").trim();
            if (title.isEmpty()) continue;
            out.add(new FindingDraft(Json.text(n, "kind", defaultKind()), Text.truncate(title, 280),
                    Json.text(n, "detail", ""), clamp(Json.num(n, "confidence", 0.6)), longs(n.get("evidenceRefs"))));
        }
        return out;
    }

    /** Responds to conflicts the Devil's Advocate raised against this agent's findings. */
    public Reasoned<List<Revision>> respond(AgentContext ctx, List<Conflict> mine, String engine) {
        String list = mine.stream().map(c -> "[conflict " + c.id() + "] on finding " + c.findingId() + " (" + c.severity() + "): " + c.objection())
                .collect(Collectors.joining("\n"));
        String prompt = ctx.caseFile(true)
                + "\n# Objections raised against YOUR findings\n" + list
                + "\n\nFor each conflict decide: \"revise\" (rewrite the finding to address the objection), \"defend\" (keep it, with a "
                + "rationale grounded in the evidence) or \"withdraw\" (the objection is right and the finding should be dropped). "
                + "Return JSON: {\"responses\": [{\"conflictId\": id, \"action\": \"revise|defend|withdraw\", "
                + "\"revisedDetail\": \"new finding text if revising, else empty\", \"revisedConfidence\": 0.0-1.0, \"rationale\": \"why\"}]}";
        Function<JsonNode, List<Revision>> parser = json -> {
            List<Revision> out = new ArrayList<>();
            JsonNode arr = json.get("responses");
            if (arr == null || !arr.isArray()) throw new IllegalArgumentException("responses array missing");
            for (JsonNode n : arr) {
                String action = Json.text(n, "action", "defend").toLowerCase();
                if (!List.of("revise", "defend", "withdraw").contains(action)) action = "defend";
                out.add(new Revision(n.path("conflictId").asLong(), action, Json.text(n, "revisedDetail", ""),
                        clamp(Json.num(n, "revisedConfidence", 0.5)), Json.text(n, "rationale", "")));
            }
            return out;
        };
        return reason(engine, prompt, parser, () -> respondWithRules(ctx, mine));
    }

    protected List<Revision> respondWithRules(AgentContext ctx, List<Conflict> mine) {
        Map<Long, Finding> byId = ctx.findings().stream().collect(Collectors.toMap(Finding::id, f -> f));
        List<Revision> out = new ArrayList<>();
        for (Conflict c : mine) {
            Finding f = byId.get(c.findingId());
            if (f == null) continue;
            if ("high".equals(c.severity())) {
                out.add(new Revision(c.id(), "defend", "", f.confidence(),
                        "The rule-based engine cannot settle a high-severity objection automatically; this needs a human decision or more evidence."));
            } else if (!f.evidenceRefs().isEmpty()) {
                out.add(new Revision(c.id(), "defend", "", f.confidence(),
                        "Finding is directly linked to uploaded evidence " + f.evidenceRefs() + "."));
            } else {
                out.add(new Revision(c.id(), "revise",
                        f.detail() + " (Revised: not yet corroborated by evidence; treat as provisional.)",
                        clamp(f.confidence() - 0.15), "Confidence lowered because the finding lacks corroborating evidence."));
            }
        }
        return out;
    }

    /** Evidence items whose text overlaps the given statement. */
    protected static List<Long> corroborating(AgentContext ctx, String statement) {
        List<Long> refs = new ArrayList<>();
        for (EvidenceItem e : ctx.evidence()) {
            String body = e.text() + " " + (e.visionSummary() == null ? "" : e.visionSummary());
            if (Text.similarity(statement, body) >= 0.34) refs.add(e.id());
        }
        return refs;
    }
}
