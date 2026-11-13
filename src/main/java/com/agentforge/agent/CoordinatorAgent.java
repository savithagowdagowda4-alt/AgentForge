package com.agentforge.agent;

import com.agentforge.agent.Models.Plan;
import com.agentforge.ai.AiService;
import com.agentforge.common.Json;
import com.agentforge.common.Text;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/** Decomposes the task, extracts editable assumptions and decides whether location matters. */
@Component
public class CoordinatorAgent extends Agent {
    private static final Pattern ASSUMPTION = Pattern.compile(
            "\\b(assum|expect|if |will |budget|cost|within|deadline|plan|estimate|target|by \\d|forecast)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GEO = Pattern.compile(
            "\\b(near|nearby|local|city|town|area|route|travel|commute|site|store|shop|office|neighbou?rhood|region|deliver|map|where|location|venue|clinic|hospital|restaurant)\\b",
            Pattern.CASE_INSENSITIVE);

    public CoordinatorAgent(AiService ai) {
        super(ai);
    }

    @Override
    public AgentRole role() {
        return AgentRole.COORDINATOR;
    }

    @Override
    protected String persona() {
        return "You coordinate the team: restate the problem precisely, surface the assumptions it rests on (so the user can "
                + "change them later in the What-If simulator) and give each specialist a focused assignment.";
    }

    public Reasoned<Plan> plan(AgentContext ctx, boolean hasLocation, String engine) {
        String prompt = ctx.caseFile(false)
                + "\n# Your job\nProduce a plan. Return JSON: {\"summary\": \"one-paragraph restatement of the decision to be made\", "
                + "\"assumptions\": [\"3-6 explicit, editable assumptions the analysis depends on\"], "
                + "\"subtasks\": {\"analyst\": \"...\", \"inspector\": \"...\", \"risk\": \"...\"}, "
                + "\"locationRelevant\": " + (hasLocation ? "true|false (true only if the answer genuinely depends on geography)" : "false")
                + ", \"geoQueries\": [\"up to 3 short place-search phrases, e.g. 'pharmacy', only if locationRelevant\"]}";
        return reason(engine, prompt, json -> parse(json, hasLocation), () -> rules(ctx, hasLocation));
    }

    private Plan parse(JsonNode json, boolean hasLocation) {
        Map<String, String> sub = new LinkedHashMap<>();
        JsonNode s = json.get("subtasks");
        if (s != null && s.isObject()) s.fields().forEachRemaining(e -> sub.put(e.getKey().toLowerCase(), e.getValue().asText()));
        List<String> assumptions = strings(json.get("assumptions"));
        if (assumptions.isEmpty()) throw new IllegalArgumentException("no assumptions returned");
        boolean geo = hasLocation && json.path("locationRelevant").asBoolean(false);
        return new Plan(Json.text(json, "summary", ""), assumptions, sub, geo, geo ? strings(json.get("geoQueries")) : List.of());
    }

    private Plan rules(AgentContext ctx, boolean hasLocation) {
        List<String> sentences = Text.sentences(ctx.description());
        List<String> assumptions = new ArrayList<>();
        for (String s : sentences) {
            if (assumptions.size() >= 6) break;
            if (ASSUMPTION.matcher(s).find() || !Text.numbers(s).isEmpty()) assumptions.add(s);
        }
        if (assumptions.isEmpty()) assumptions.addAll(sentences.subList(0, Math.min(3, sentences.size())));
        if (assumptions.isEmpty()) assumptions.add("The task description is complete and accurate.");
        boolean geo = hasLocation && GEO.matcher(ctx.description()).find();
        List<String> geoQueries = new ArrayList<>();
        if (geo) {
            var m = GEO.matcher(ctx.description());
            while (m.find() && geoQueries.size() < 2) {
                String w = m.group(1).toLowerCase();
                if (Set.of("clinic", "hospital", "restaurant", "store", "shop", "office", "venue").contains(w) && !geoQueries.contains(w))
                    geoQueries.add(w);
            }
        }
        List<String> kw = Text.topKeywords(ctx.description(), 5);
        Map<String, String> sub = new LinkedHashMap<>();
        sub.put("analyst", "Establish the key facts and options around: " + String.join(", ", kw));
        sub.put("inspector", ctx.evidence().isEmpty() ? "No evidence uploaded; record the evidence gap."
                : "Check the " + ctx.evidence().size() + " uploaded item(s) against the description.");
        sub.put("risk", "Identify risks in the stated plan and its assumptions.");
        String summary = sentences.isEmpty() ? ctx.title() : sentences.get(0);
        return new Plan(summary, assumptions, sub, geo, geoQueries);
    }
}
