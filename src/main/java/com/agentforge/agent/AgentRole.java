package com.agentforge.agent;

import java.util.Locale;

/** The six agents on the AgentForge team. */
public enum AgentRole {
    COORDINATOR("Coordinator", "Breaks the problem down, extracts assumptions and assigns work."),
    ANALYST("Research Analyst", "Establishes the key facts, drivers and options."),
    INSPECTOR("Evidence Inspector", "Examines uploaded evidence and checks it against the description."),
    RISK("Risk Assessor", "Identifies risks, failure modes and their severity."),
    CRITIC("Devil's Advocate", "Challenges weak or unsupported findings and raises conflicts."),
    SYNTHESIZER("Synthesizer", "Weighs the debate and produces the final recommendation.");

    private final String label;
    private final String mission;

    AgentRole(String label, String mission) {
        this.label = label;
        this.mission = mission;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return label;
    }

    public String mission() {
        return mission;
    }

    public static AgentRole of(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
