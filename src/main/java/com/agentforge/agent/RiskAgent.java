package com.agentforge.agent;

import com.agentforge.agent.Models.FindingDraft;
import com.agentforge.ai.AiService;
import com.agentforge.common.Text;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/** Identifies risks, failure modes and their severity. */
@Component
public class RiskAgent extends SpecialistAgent {
    private static final Map<String, Pattern> LEXICON = new LinkedHashMap<>();

    static {
        LEXICON.put("Schedule risk", Pattern.compile("\\b(deadline|urgent|asap|tight|delay|late|launch|by (next|end))", Pattern.CASE_INSENSITIVE));
        LEXICON.put("Financial risk", Pattern.compile("(\\bcost|budget|expensive|price|spend|revenue|loss|[$€£₹]\\d)", Pattern.CASE_INSENSITIVE));
        LEXICON.put("Compliance risk", Pattern.compile("\\b(legal|complian|regulat|contracts?\\b|gdpr|licen[cs]e|policy)", Pattern.CASE_INSENSITIVE));
        LEXICON.put("Security risk", Pattern.compile("\\b(security|breach|password|privacy|personal data|attack)", Pattern.CASE_INSENSITIVE));
        LEXICON.put("Dependency risk", Pattern.compile("\\b(depend|vendor|supplier|third[- ]party|partner|integration)", Pattern.CASE_INSENSITIVE));
        LEXICON.put("Uncertainty risk", Pattern.compile("\\b(uncertain|unknown|unclear|maybe|might|not sure|assume)", Pattern.CASE_INSENSITIVE));
        LEXICON.put("Resourcing risk", Pattern.compile("\\b(staff|team|hire|hiring|people|skill|capacity|workload)", Pattern.CASE_INSENSITIVE));
    }

    public RiskAgent(AiService ai) {
        super(ai);
    }

    @Override
    public AgentRole role() {
        return AgentRole.RISK;
    }

    @Override
    protected String persona() {
        return "You assess risk: what could go wrong, how likely, how severe, and what would mitigate it. Every finding should be "
                + "kind \"risk\" and its detail should state likelihood, impact and a mitigation.";
    }

    @Override
    protected String focus() {
        return "List the most important risks, including risks that come from the working assumptions being wrong.";
    }

    @Override
    protected String defaultKind() {
        return "risk";
    }

    @Override
    protected List<FindingDraft> analyzeWithRules(AgentContext ctx) {
        List<FindingDraft> out = new ArrayList<>();
        String corpus = ctx.scenario() ? String.join("\n", ctx.assumptions()) : ctx.description() + "\n" + String.join("\n", ctx.assumptions());
        List<String> sentences = Text.sentences(corpus);
        for (Map.Entry<String, Pattern> e : LEXICON.entrySet()) {
            if (out.size() >= 4) break;
            for (String s : sentences) {
                if (e.getValue().matcher(s).find()) {
                    out.add(new FindingDraft("risk", e.getKey() + ": " + Text.truncate(s, 90),
                            "Triggered by: \"" + s + "\". Likelihood and impact cannot be quantified by the rule-based engine; "
                                    + "mitigate by validating this point before committing.", 0.6, corroborating(ctx, s)));
                    break;
                }
            }
        }
        if (out.isEmpty()) out.add(new FindingDraft("risk", "No explicit risks stated",
                "Nothing in the description signals a specific risk. Residual risk is unassessed rather than absent.", 0.4, List.of()));
        return out;
    }
}
