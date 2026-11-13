package com.agentforge.agent;

import com.agentforge.agent.Models.Challenge;
import com.agentforge.agent.Models.Finding;
import com.agentforge.ai.AiService;
import com.agentforge.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The Devil's Advocate: reviews every finding and raises conflicts against weak ones. */
@Component
public class CriticAgent extends Agent {

    public CriticAgent(AiService ai) {
        super(ai);
    }

    @Override
    public AgentRole role() {
        return AgentRole.CRITIC;
    }

    @Override
    protected String persona() {
        return "You are the Devil's Advocate. Scrutinise the other agents' findings for unsupported claims, contradictions with "
                + "the evidence, overconfidence and missing alternatives. Only object when the objection is substantive. "
                + "Use severity \"high\" only when the issue could change the final recommendation.";
    }

    public Reasoned<List<Challenge>> review(AgentContext ctx, String engine) {
        Set<Long> valid = ctx.findings().stream().map(Finding::id).collect(Collectors.toSet());
        String prompt = ctx.caseFile(true)
                + "\n# Debate intensity: " + ctx.intensity().toUpperCase() + "\n" + switch (ctx.intensity()) {
                    case "gentle" -> "Only object to serious problems that could change the decision (0-2 objections).";
                    case "ruthless" -> "Be relentless: challenge every unsupported, overconfident or one-sided finding (up to 6 objections).";
                    default -> "Challenge the findings that deserve it (0-4 objections).";
                }
                + "\n# Your job\nReturn JSON: {\"challenges\": "
                + "[{\"findingId\": id, \"objection\": \"specific objection\", \"severity\": \"low|medium|high\"}]}";
        return reason(engine, prompt, json -> {
            List<Challenge> out = new ArrayList<>();
            JsonNode arr = json.get("challenges");
            if (arr == null || !arr.isArray()) throw new IllegalArgumentException("challenges array missing");
            for (JsonNode n : arr) {
                long id = n.path("findingId").asLong(-1);
                if (!valid.contains(id)) continue;
                String sev = Json.text(n, "severity", "medium").toLowerCase();
                if (!Set.of("low", "medium", "high").contains(sev)) sev = "medium";
                out.add(new Challenge(id, Json.text(n, "objection", ""), sev));
            }
            return out;
        }, () -> rules(ctx));
    }

    private List<Challenge> rules(AgentContext ctx) {
        List<Challenge> out = new ArrayList<>();
        boolean hasEvidence = !ctx.evidence().isEmpty();
        String mode = ctx.intensity();
        int max = "gentle".equals(mode) ? 2 : "ruthless".equals(mode) ? 6 : 4;
        double lowConf = "gentle".equals(mode) ? 0.45 : "ruthless".equals(mode) ? 0.72 : 0.6;
        for (Finding f : ctx.findings()) {
            if (out.size() >= max) break;
            if ("inconsistency".equals(f.kind())) {
                out.add(new Challenge(f.id(), "This inconsistency must be resolved before any recommendation can rely on the affected figure.", "high"));
            } else if (f.confidence() < lowConf && !"gap".equals(f.kind())) {
                out.add(new Challenge(f.id(), "Confidence is low (" + String.format("%.2f", f.confidence()) + "); this should not drive the decision without corroboration.", "medium"));
            } else if (!"gentle".equals(mode) && hasEvidence && f.evidenceRefs().isEmpty() && "claim".equals(f.kind())) {
                out.add(new Challenge(f.id(), "Evidence was uploaded, but this claim is not linked to any of it.", "low"));
            } else if ("ruthless".equals(mode) && "risk".equals(f.kind()) && f.evidenceRefs().isEmpty()) {
                out.add(new Challenge(f.id(), "This risk is asserted without evidence of likelihood or impact; quantify it or downgrade it.", "low"));
            }
        }
        return out;
    }
}
