package com.agentforge.agent;

import org.springframework.stereotype.Component;

import java.util.List;

/** Registry of the six agents, injected wherever the team as a whole is needed. */
@Component
public class AgentTeam {
    private final CoordinatorAgent coordinator;
    private final AnalystAgent analyst;
    private final InspectorAgent inspector;
    private final RiskAgent risk;
    private final CriticAgent critic;
    private final SynthesizerAgent synthesizer;

    public AgentTeam(CoordinatorAgent coordinator, AnalystAgent analyst, InspectorAgent inspector, RiskAgent risk,
                     CriticAgent critic, SynthesizerAgent synthesizer) {
        this.coordinator = coordinator;
        this.analyst = analyst;
        this.inspector = inspector;
        this.risk = risk;
        this.critic = critic;
        this.synthesizer = synthesizer;
    }

    public CoordinatorAgent coordinator() { return coordinator; }
    public CriticAgent critic() { return critic; }
    public SynthesizerAgent synthesizer() { return synthesizer; }

    public List<SpecialistAgent> specialists() {
        return List.of(analyst, inspector, risk);
    }

    public SpecialistAgent specialist(AgentRole role) {
        return specialists().stream().filter(s -> s.role() == role).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a specialist: " + role));
    }

    public List<Agent> all() {
        return List.of(coordinator, analyst, inspector, risk, critic, synthesizer);
    }
}
