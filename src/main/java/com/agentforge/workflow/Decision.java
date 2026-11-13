package com.agentforge.workflow;

import java.util.Map;

/**
 * A human-in-the-loop decision submitted at a checkpoint.
 *
 * @param action          "approve" to resume, "stop" to end the run
 * @param note            optional guidance passed to the agents
 * @param conflictChoices conflict id -> "uphold" (keep the finding) or "reject" (accept the objection)
 */
public record Decision(String action, String note, Map<Long, String> conflictChoices) {
    public boolean approve() {
        return "approve".equals(action);
    }

    public boolean stop() {
        return "stop".equals(action);
    }
}
