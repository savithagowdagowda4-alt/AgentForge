package com.agentforge.web;

import com.agentforge.auth.CurrentUser;
import com.agentforge.evidence.EvidenceService;
import com.agentforge.insight.ChatService;
import com.agentforge.insight.InsightsService;
import com.agentforge.task.TaskService;
import com.agentforge.task.TemplateCatalog;
import com.agentforge.workflow.Orchestrator;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Ask-the-Agents chat, analytics insights and scenario templates. */
@RestController
@RequestMapping("/api")
public class FeatureController {
    private final CurrentUser user;
    private final ChatService chat;
    private final InsightsService insights;
    private final TemplateCatalog templates;
    private final TaskService tasks;
    private final EvidenceService evidence;
    private final Orchestrator orchestrator;

    public FeatureController(CurrentUser user, ChatService chat, InsightsService insights, TemplateCatalog templates,
                             TaskService tasks, EvidenceService evidence, Orchestrator orchestrator) {
        this.user = user;
        this.chat = chat;
        this.insights = insights;
        this.templates = templates;
        this.tasks = tasks;
        this.evidence = evidence;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/tasks/{id}/chat")
    public List<Map<String, Object>> history(@PathVariable long id) {
        return chat.history(id, user.id());
    }

    @PostMapping("/tasks/{id}/chat")
    public Map<String, Object> ask(@PathVariable long id, @RequestBody Map<String, String> body) {
        return chat.ask(id, user.id(), body.get("question"));
    }

    @DeleteMapping("/tasks/{id}/chat")
    public Map<String, Object> clear(@PathVariable long id) {
        chat.clear(id, user.id());
        return Map.of("ok", true);
    }

    @GetMapping("/insights")
    public Map<String, Object> insights() {
        return insights.insights(user.id());
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return templates.list();
    }

    /** Creates a task from a template, attaches its sample evidence and starts the analysis immediately. */
    @PostMapping("/templates/{templateId}/launch")
    public Map<String, Object> launch(@PathVariable String templateId) throws Exception {
        long uid = user.id();
        TemplateCatalog.Template t = templates.get(templateId);
        Map<String, Object> task = tasks.create(uid, t.title(), t.description(), "text", t.checkpoints(), t.location(), null, null);
        long id = ((Number) task.get("id")).longValue();
        tasks.setIntensity(id, t.intensity());
        tasks.setTemplate(id, t.id());
        for (TemplateCatalog.SampleEvidence e : t.evidence()) evidence.addText(id, uid, e.filename(), e.content(), null);
        orchestrator.start(id, uid);
        return Map.of("taskId", id);
    }
}
