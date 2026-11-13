package com.agentforge.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Value types exchanged between agents and the orchestrator. */
public final class Models {
    private Models() {
    }

    /** A piece of uploaded evidence as agents see it (user-corrected text takes precedence). */
    public record EvidenceItem(long id, String filename, String text, String visionSummary) {
    }

    /** A memory retrieved from the user's vault for reuse in this task. */
    public record MemoryHit(long id, String kind, String content, double score) {
    }

    /** A finding proposed by an agent, before persistence. */
    public record FindingDraft(String kind, String title, String detail, double confidence, List<Long> evidenceRefs) {
    }

    /** A persisted finding, visible to every agent. */
    public record Finding(long id, AgentRole agent, String kind, String title, String detail, double confidence,
                          String status, List<Long> evidenceRefs) {
    }

    /** An objection raised by the critic against a specific finding. */
    public record Challenge(long findingId, String objection, String severity) {
    }

    /** A persisted conflict between the critic and a finding's author. */
    public record Conflict(long id, long findingId, AgentRole target, String objection, String severity, String status) {
    }

    /** An agent's response to a conflict raised against its finding. */
    public record Revision(long conflictId, String action, String revisedDetail, double revisedConfidence, String rationale) {
    }

    /** The coordinator's plan for the run. */
    public record Plan(String summary, List<String> assumptions, Map<String, String> subtasks,
                       boolean locationRelevant, List<String> geoQueries) {
    }

    /** The synthesizer's final output. */
    public record Synthesis(String recommendation, double confidence, List<String> reasoning,
                            List<Long> supportingFindings, JsonNode decisionTree, List<String> geoQueries,
                            List<String> openQuestions) {
    }
}
