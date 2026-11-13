package com.agentforge.agent;

import com.agentforge.agent.Models.Finding;
import com.agentforge.agent.Models.Synthesis;
import com.agentforge.ai.AiService;
import com.agentforge.common.Json;
import com.agentforge.common.Text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** Weighs the debate and produces an explainable recommendation with a decision tree. */
@Component
public class SynthesizerAgent extends Agent {

    public SynthesizerAgent(AiService ai) {
        super(ai);
    }

    @Override
    public AgentRole role() {
        return AgentRole.SYNTHESIZER;
    }

    @Override
    protected String persona() {
        return "You produce the final recommendation. Weigh findings by confidence and status (ignore rejected or withdrawn ones), "
                + "respect the human reviewer's decisions, state remaining uncertainty honestly and explain the reasoning step by step.";
    }

    public Reasoned<Synthesis> synthesize(AgentContext ctx, String engine) {
        Set<Long> valid = ctx.findings().stream().map(Finding::id).collect(Collectors.toSet());
        String prompt = ctx.caseFile(true)
                + "\n# Your job\nReturn JSON: {\"recommendation\": \"clear recommendation in 2-4 sentences\", \"confidence\": 0.0-1.0, "
                + "\"reasoning\": [\"3-6 reasoning steps, each citing finding ids like [finding 12]\"], "
                + "\"supportingFindings\": [finding ids the recommendation rests on], "
                + "\"decisionTree\": {\"question\": \"the core decision\", \"options\": [{\"label\": \"option\", \"when\": \"condition under which to choose it\", "
                + "\"outcome\": \"expected outcome\", \"recommended\": true|false}]}, "
                + "\"geoQueries\": [" + (ctx.location() != null ? "\"up to 3 place-search phrases if useful\"" : "") + "], "
                + "\"openQuestions\": [\"questions that remain unanswered\"]}";
        return reason(engine, prompt, json -> {
            String rec = Json.text(json, "recommendation", "");
            if (rec.isBlank()) throw new IllegalArgumentException("empty recommendation");
            List<Long> support = longs(json.get("supportingFindings")).stream().filter(valid::contains).toList();
            JsonNode tree = json.has("decisionTree") && json.get("decisionTree").isObject() ? json.get("decisionTree") : Json.obj();
            return new Synthesis(rec, clamp(Json.num(json, "confidence", 0.6)), strings(json.get("reasoning")), support, tree,
                    ctx.location() != null ? strings(json.get("geoQueries")) : List.of(), strings(json.get("openQuestions")));
        }, () -> rules(ctx));
    }

    private Synthesis rules(AgentContext ctx) {
        List<Finding> active = ctx.activeFindings();
        List<Finding> risks = active.stream().filter(f -> "risk".equals(f.kind())).toList();
        List<Finding> positives = active.stream().filter(f -> !"risk".equals(f.kind()) && !"gap".equals(f.kind()) && !"inconsistency".equals(f.kind())).toList();
        long unresolved = ctx.conflicts().stream().filter(c -> "unresolved".equals(c.status())).count();
        long inconsistencies = active.stream().filter(f -> "inconsistency".equals(f.kind())).count();
        long gaps = active.stream().filter(f -> "gap".equals(f.kind())).count();

        double support = positives.stream().mapToDouble(Finding::confidence).sum();
        double against = risks.stream().mapToDouble(Finding::confidence).sum() * 0.8 + unresolved * 0.6 + inconsistencies * 0.5 + gaps * 0.3;
        double score = support - against;
        String verdict = score > 1.0 ? "Proceed" : score > 0 ? "Proceed with safeguards" : "Hold and gather more evidence";

        List<Finding> top = new ArrayList<>(positives);
        top.sort(Comparator.comparingDouble(Finding::confidence).reversed());
        List<Finding> topRisks = new ArrayList<>(risks);
        topRisks.sort(Comparator.comparingDouble(Finding::confidence).reversed());

        List<String> reasoning = new ArrayList<>();
        reasoning.add(String.format("Supporting weight %.2f from %d finding(s) vs. opposing weight %.2f from %d risk(s), %d inconsistency(ies), %d gap(s) and %d unresolved conflict(s).",
                support, positives.size(), against, risks.size(), inconsistencies, gaps, unresolved));
        top.stream().limit(2).forEach(f -> reasoning.add("Supports: [finding " + f.id() + "] " + f.title()));
        topRisks.stream().limit(2).forEach(f -> reasoning.add("Counts against: [finding " + f.id() + "] " + f.title()));
        if (!ctx.userNotes().isEmpty()) reasoning.add("Human reviewer input considered: " + Text.truncate(String.join("; ", ctx.userNotes()), 200));

        String rec = verdict + ". " + switch (verdict) {
            case "Proceed" -> "The supporting findings clearly outweigh the identified risks.";
            case "Proceed with safeguards" -> "The case is positive on balance, but the listed risks need explicit mitigations before committing.";
            default -> "Risks, inconsistencies or open conflicts currently outweigh the support; resolve them before deciding.";
        };
        double confidence = clamp(active.isEmpty() ? 0.3 : active.stream().mapToDouble(Finding::confidence).average().orElse(0.5) * (1 - 0.1 * unresolved));

        ObjectNode tree = Json.obj();
        tree.put("question", "Should we go ahead with: " + Text.truncate(ctx.title(), 80) + "?");
        ArrayNode options = tree.putArray("options");
        addOption(options, "Proceed", "Risks are accepted or mitigated", "Fastest path to the objective", verdict.equals("Proceed"));
        addOption(options, "Proceed with safeguards", topRisks.isEmpty() ? "Monitoring is in place" : "Mitigate: " + Text.truncate(topRisks.get(0).title(), 60),
                "Objective pursued with reduced downside", verdict.equals("Proceed with safeguards"));
        addOption(options, "Hold", "Inconsistencies or gaps remain", "Time spent gathering evidence; lower risk of a wrong decision", verdict.startsWith("Hold"));

        List<String> open = new ArrayList<>();
        active.stream().filter(f -> "gap".equals(f.kind()) || "inconsistency".equals(f.kind())).forEach(f -> open.add(f.title()));
        return new Synthesis(rec, confidence, reasoning, top.stream().limit(4).map(Finding::id).toList(), tree,
                ctx.plan() != null ? ctx.plan().geoQueries() : List.of(), open);
    }

    private static void addOption(ArrayNode options, String label, String when, String outcome, boolean recommended) {
        ObjectNode o = options.addObject();
        o.put("label", label);
        o.put("when", when);
        o.put("outcome", outcome);
        o.put("recommended", recommended);
    }
}
