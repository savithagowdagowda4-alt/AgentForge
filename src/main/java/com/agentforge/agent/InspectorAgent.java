package com.agentforge.agent;

import com.agentforge.agent.Models.EvidenceItem;
import com.agentforge.agent.Models.FindingDraft;
import com.agentforge.ai.AiService;
import com.agentforge.common.Text;
import org.springframework.stereotype.Component;

import java.util.*;

/** Examines uploaded evidence and detects inconsistencies with the user's description. */
@Component
public class InspectorAgent extends SpecialistAgent {

    public InspectorAgent(AiService ai) {
        super(ai);
    }

    @Override
    public AgentRole role() {
        return AgentRole.INSPECTOR;
    }

    @Override
    protected String persona() {
        return "You are the evidence specialist. You read the text and visual analysis extracted from uploaded images, charts "
                + "and documents, summarise what they actually show, and flag every inconsistency between the user's description "
                + "and the evidence (numbers, dates, names, trends) as kind \"inconsistency\". If there is no evidence, report the gap.";
    }

    @Override
    protected String focus() {
        return "Summarise what each piece of evidence shows and list inconsistencies between the description and the evidence.";
    }

    @Override
    protected String defaultKind() {
        return "evidence";
    }

    @Override
    protected List<FindingDraft> analyzeWithRules(AgentContext ctx) {
        List<FindingDraft> out = new ArrayList<>();
        if (ctx.evidence().isEmpty()) {
            out.add(new FindingDraft("gap", "No supporting evidence supplied",
                    "The analysis relies entirely on the user's description; nothing has been uploaded to verify it.", 0.9, List.of()));
            return out;
        }
        for (EvidenceItem e : ctx.evidence()) {
            List<String> s = Text.sentences(e.text());
            String gist = s.isEmpty() ? "(no readable text extracted)" : Text.truncate(String.join(" ", s.subList(0, Math.min(2, s.size()))), 240);
            out.add(new FindingDraft("evidence", "Evidence: " + Text.truncate(e.filename(), 80),
                    "Extracted content: " + gist + (e.visionSummary() != null ? " Visual analysis: " + Text.truncate(e.visionSummary(), 200) : ""),
                    s.isEmpty() ? 0.4 : 0.8, List.of(e.id())));
        }
        out.addAll(numericInconsistencies(ctx));
        return out;
    }

    /** Numbers in the description that do not appear in the evidence, where evidence has a comparable figure. */
    private List<FindingDraft> numericInconsistencies(AgentContext ctx) {
        List<FindingDraft> out = new ArrayList<>();
        Map<String, List<String>> evByUnit = new HashMap<>();
        Map<String, Long> evSource = new HashMap<>();
        for (EvidenceItem e : ctx.evidence()) {
            for (String n : Text.numbers(e.text())) {
                evByUnit.computeIfAbsent(unit(n), k -> new ArrayList<>()).add(n);
                evSource.putIfAbsent(n, e.id());
            }
        }
        Set<String> seen = new HashSet<>();
        for (String n : Text.numbers(ctx.workingText())) {
            String u = unit(n);
            if (u.isEmpty() || !seen.add(n)) continue; // only compare figures with a unit (%, $, days...)
            List<String> candidates = evByUnit.getOrDefault(u, List.of());
            if (candidates.isEmpty() || candidates.contains(n)) continue;
            String ev = candidates.get(0);
            out.add(new FindingDraft("inconsistency", "Description says " + n + ", evidence shows " + ev,
                    "The description states a figure of " + n + " but the closest comparable figure in the uploaded evidence is " + ev
                            + ". One of them may be outdated or mistaken.", 0.6, List.of(evSource.get(ev))));
            if (out.size() >= 3) break;
        }
        return out;
    }

    private static String unit(String n) {
        if (n.startsWith("$") || n.startsWith("€") || n.startsWith("£") || n.startsWith("₹")) return "currency";
        String u = n.replaceAll("[0-9.$€£₹]", "");
        if (u.startsWith("day")) return "day";
        if (u.startsWith("week")) return "week";
        if (u.startsWith("month")) return "month";
        if (u.startsWith("year")) return "year";
        if (u.startsWith("hour")) return "hour";
        return u;
    }
}
