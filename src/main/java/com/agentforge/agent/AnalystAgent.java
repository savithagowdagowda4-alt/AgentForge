package com.agentforge.agent;

import com.agentforge.agent.Models.FindingDraft;
import com.agentforge.ai.AiService;
import com.agentforge.common.Text;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Establishes key facts, drivers and options. */
@Component
public class AnalystAgent extends SpecialistAgent {

    public AnalystAgent(AiService ai) {
        super(ai);
    }

    @Override
    public AgentRole role() {
        return AgentRole.ANALYST;
    }

    @Override
    protected String persona() {
        return "You establish the facts: the objective, the key drivers, the realistic options and what each implies. "
                + "Reuse relevant memory-vault findings when they apply and cite them by memory id in the detail.";
    }

    @Override
    protected String focus() {
        return "Identify the objective, the key facts and drivers, and the main options available.";
    }

    @Override
    protected List<FindingDraft> analyzeWithRules(AgentContext ctx) {
        List<FindingDraft> out = new ArrayList<>();
        List<String> sentences = Text.sentences(ctx.workingText());
        if (!sentences.isEmpty()) {
            out.add(new FindingDraft("finding", "Objective: " + Text.truncate(sentences.get(0), 120),
                    "The task is framed around: \"" + sentences.get(0) + "\". All other findings are weighed against this objective.",
                    0.8, corroborating(ctx, sentences.get(0))));
        }
        for (String s : sentences.subList(Math.min(1, sentences.size()), sentences.size())) {
            if (out.size() >= 4) break;
            if (Text.numbers(s).isEmpty() && Text.tokens(s).size() < 6) continue;
            List<Long> refs = corroborating(ctx, s);
            out.add(new FindingDraft("claim", "Stated fact: " + Text.truncate(s, 110),
                    "The description asserts: \"" + s + "\"." + (refs.isEmpty() ? " No uploaded evidence corroborates this yet." :
                            " This is consistent with uploaded evidence."), refs.isEmpty() ? 0.55 : 0.75, refs));
        }
        for (var m : ctx.memories()) {
            if (out.size() >= 5) break;
            out.add(new FindingDraft("finding", "Prior finding reused (memory " + m.id() + ")",
                    "A previous task reached a related conclusion: \"" + Text.truncate(m.content(), 300) + "\".", 0.6, List.of()));
        }
        if (out.isEmpty()) out.add(new FindingDraft("gap", "Description too short to analyse",
                "The task description does not contain enough detail for rule-based analysis.", 0.9, List.of()));
        return out;
    }
}
