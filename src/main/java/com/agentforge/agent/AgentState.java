package com.agentforge.agent;

import java.util.Locale;

/** Lifecycle state of an agent within a single analysis run (shown on the Live Agent Radar). */
public enum AgentState {
    IDLE, PROCESSING, REVIEWING, WAITING, COMPLETED, ERROR;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
