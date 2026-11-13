package com.agentforge.agent;

import com.agentforge.ai.AiService;
import com.agentforge.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Base class for every AgentForge agent.
 * <p>
 * Implements the template method {@link #reason}: when the AI service is available and the run uses the
 * "claude" engine, the agent sends its role prompt plus the shared case file to Claude and parses a JSON
 * answer; otherwise (or if the call fails) it runs its own deterministic rule-based implementation.
 * The returned {@link Reasoned} records which path actually produced the result, so the UI never
 * presents rule-based output as model output.
 */
public abstract class Agent {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final AiService ai;

    /** Result plus provenance. */
    public record Reasoned<T>(T value, String engine, String note) {
    }

    protected Agent(AiService ai) {
        this.ai = ai;
    }

    public abstract AgentRole role();

    /** Role-specific system prompt for model-based reasoning. */
    protected abstract String persona();

    protected String systemPrompt() {
        return "You are the " + role().label() + " in AgentForge, a team of six AI agents that analyse a user's problem "
                + "together (Coordinator, Research Analyst, Evidence Inspector, Risk Assessor, Devil's Advocate, Synthesizer). "
                + persona()
                + "\nBase every statement on the case file. Do not invent evidence, numbers or sources; if something is unknown, say so. "
                + "Respond with a single JSON object only, matching the requested schema, with no surrounding prose.";
    }

    protected <T> Reasoned<T> reason(String engine, String instructions, Function<JsonNode, T> parser, Supplier<T> rules) {
        if ("claude".equals(engine) && ai.available()) {
            try {
                String raw = ai.complete(systemPrompt(), instructions, 8000);
                return new Reasoned<>(parser.apply(Json.extractObject(raw)), "claude", null);
            } catch (Exception e) {
                log.warn("{} model call failed, using rule-based reasoning: {}", role(), e.getMessage());
                return new Reasoned<>(rules.get(), "rules", "Claude call failed (" + e.getMessage() + "); rule-based reasoning used instead");
            }
        }
        return new Reasoned<>(rules.get(), "rules", null);
    }

    protected static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> {
            String s = n.asText("").trim();
            if (!s.isEmpty()) out.add(s);
        });
        return out;
    }

    protected static List<Long> longs(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> {
            if (n.canConvertToLong()) out.add(n.asLong());
            else {
                String digits = n.asText("").replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) out.add(Long.parseLong(digits));
            }
        });
        return out;
    }

    protected static double clamp(double v) {
        return Math.max(0.05, Math.min(0.99, v));
    }
}
