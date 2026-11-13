package com.agentforge.agent;

import com.agentforge.agent.Models.*;
import com.agentforge.common.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared case file for one analysis run. Agents read from it; the orchestrator updates it
 * between stages so each agent sees the work of the others.
 */
public class AgentContext {
    private final long taskId;
    private final int version;
    private final String title;
    private final String description;
    private final String location; // null unless the task is location-relevant
    private final List<EvidenceItem> evidence;
    private final List<MemoryHit> memories;
    private final List<String> userNotes = new ArrayList<>();
    private List<String> assumptions;
    private Plan plan;
    private boolean scenario;
    private List<Finding> findings = List.of();
    private List<Conflict> conflicts = List.of();

    public AgentContext(long taskId, int version, String title, String description, String location,
                        List<String> assumptions, List<EvidenceItem> evidence, List<MemoryHit> memories) {
        this.taskId = taskId;
        this.version = version;
        this.title = title;
        this.description = description;
        this.location = location;
        this.assumptions = new ArrayList<>(assumptions);
        this.evidence = new ArrayList<>(evidence);
        this.memories = memories;
    }

    public long taskId() { return taskId; }
    public int version() { return version; }
    public String title() { return title; }
    public String description() { return description; }
    public String location() { return location; }
    public List<EvidenceItem> evidence() { return evidence; }
    public List<MemoryHit> memories() { return memories; }
    public List<String> assumptions() { return assumptions; }
    public Plan plan() { return plan; }
    public List<Finding> findings() { return findings; }
    public List<Conflict> conflicts() { return conflicts; }
    public List<String> userNotes() { return userNotes; }

    public void setAssumptions(List<String> a) { this.assumptions = new ArrayList<>(a); }
    public void setPlan(Plan plan) { this.plan = plan; }
    public boolean scenario() { return scenario; }
    public void setScenario(boolean s) { this.scenario = s; }

    private String intensity = "balanced";
    /** Debate intensity chosen by the user: gentle | balanced | ruthless. */
    public String intensity() { return intensity; }
    public void setIntensity(String i) { this.intensity = i == null ? "balanced" : i; }

    /** Text rule-based agents analyse: in a What-If scenario the edited assumptions supersede the description. */
    public String workingText() { return scenario ? String.join(". ", assumptions) : description; }
    public void setFindings(List<Finding> f) { this.findings = List.copyOf(f); }
    public void setConflicts(List<Conflict> c) { this.conflicts = List.copyOf(c); }
    public void replaceEvidence(List<EvidenceItem> items) { evidence.clear(); evidence.addAll(items); }

    public List<Finding> activeFindings() {
        return findings.stream().filter(f -> !"rejected".equals(f.status()) && !"withdrawn".equals(f.status())).toList();
    }

    public String evidenceText() {
        return evidence.stream().map(e -> e.text() + " " + (e.visionSummary() == null ? "" : e.visionSummary()))
                .collect(Collectors.joining("\n"));
    }

    /** Renders the case file as prompt text for model-based agents. */
    public String caseFile(boolean includeFindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Task\nTitle: ").append(title).append("\n\n").append(description).append("\n");
        if (location != null) sb.append("\nUser-supplied location (task is location-dependent): ").append(location).append("\n");
        if (!assumptions.isEmpty()) {
            sb.append("\n# Working assumptions\n");
            for (int i = 0; i < assumptions.size(); i++) sb.append("A").append(i + 1).append(". ").append(assumptions.get(i)).append("\n");
        }
        if (!evidence.isEmpty()) {
            sb.append("\n# Uploaded evidence\n");
            for (EvidenceItem e : evidence) {
                sb.append("[evidence ").append(e.id()).append("] ").append(e.filename()).append("\n");
                if (e.visionSummary() != null && !e.visionSummary().isBlank())
                    sb.append("Visual analysis: ").append(Text.truncate(e.visionSummary(), 1500)).append("\n");
                sb.append("Extracted text: ").append(Text.truncate(e.text(), 6000)).append("\n\n");
            }
        } else {
            sb.append("\n# Uploaded evidence\n(none)\n");
        }
        if (!memories.isEmpty()) {
            sb.append("\n# Relevant findings from this user's previous tasks (memory vault)\n");
            for (MemoryHit m : memories) sb.append("[memory ").append(m.id()).append("] ").append(Text.truncate(m.content(), 600)).append("\n");
        }
        if (!userNotes.isEmpty()) {
            sb.append("\n# Notes and decisions from the human reviewer\n");
            userNotes.forEach(n -> sb.append("- ").append(n).append("\n"));
        }
        if (includeFindings && !findings.isEmpty()) {
            sb.append("\n# Findings so far\n");
            for (Finding f : findings) {
                sb.append("[finding ").append(f.id()).append("] (").append(f.agent().label()).append(", ").append(f.kind())
                        .append(", confidence ").append(String.format("%.2f", f.confidence())).append(", status ").append(f.status())
                        .append(") ").append(f.title()).append(": ").append(Text.truncate(f.detail(), 700));
                if (!f.evidenceRefs().isEmpty()) sb.append(" [evidence ").append(f.evidenceRefs()).append("]");
                sb.append("\n");
            }
        }
        if (includeFindings && !conflicts.isEmpty()) {
            sb.append("\n# Conflicts\n");
            for (Conflict c : conflicts)
                sb.append("[conflict ").append(c.id()).append("] on finding ").append(c.findingId()).append(" (").append(c.severity())
                        .append(", ").append(c.status()).append("): ").append(c.objection()).append("\n");
        }
        return sb.toString();
    }

    public Map<String, Object> summary() {
        return Map.of("taskId", taskId, "version", version, "evidence", evidence.size(), "memories", memories.size());
    }
}
