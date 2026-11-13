package com.agentforge;

import com.agentforge.auth.AuthService;
import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.insight.ChatService;
import com.agentforge.insight.InsightsService;
import com.agentforge.task.TaskService;
import com.agentforge.task.TemplateCatalog;
import com.agentforge.web.FeatureController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Templates launch, debate intensity, Ask-the-Agents chat and insights, on the rule-based engine. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:affeatures;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "agentforge.ai.enabled=false",
        "agentforge.geo.enabled=false",
        "agentforge.storage.dir=./target/test-uploads"})
class FeaturesIntegrationTest {
    @Autowired AuthService auth;
    @Autowired TaskService tasks;
    @Autowired TemplateCatalog templates;
    @Autowired FeatureController features;
    @Autowired ChatService chat;
    @Autowired InsightsService insights;
    @Autowired Db db;

    @Test
    void templateLaunchChatAndInsights() throws Exception {
        long uid = ((Number) auth.register("tpl" + System.nanoTime() % 100000, "secret123", "T").get("id")).longValue();
        signIn(uid);
        assertEquals(5, templates.list().size());

        // "ruthless" vendor template with two evidence files and a before_synthesis checkpoint
        long taskId = ((Number) features.launch("vendor-selection").get("taskId")).longValue();
        assertEquals("ruthless", Db.str(tasks.get(taskId), "debate_intensity"));
        assertEquals(2, db.list("SELECT id FROM evidence WHERE task_id=?", taskId).size());
        assertEquals("awaiting_approval", waitFor(taskId, Set.of("awaiting_approval", "completed", "failed")));

        // Chat is refused until the analysis has completed
        assertThrows(ApiException.class, () -> chat.ask(taskId, uid, "Why?"));

        // Approve every checkpoint until completion
        for (int i = 0; i < 5 && !"completed".equals(Db.str(tasks.get(taskId), "status")); i++) {
            features.getClass(); // keep reference
            var gate = db.list("SELECT id FROM conflicts WHERE task_id=? AND status='unresolved'", taskId);
            Map<Long, String> choices = new HashMap<>();
            gate.forEach(c -> choices.put(((Number) c.get("id")).longValue(), "uphold"));
            try {
                orchestrator().decide(taskId, uid, new com.agentforge.workflow.Decision("approve", null, choices));
            } catch (ApiException ignored) {
            }
            waitFor(taskId, Set.of("awaiting_approval", "completed", "failed"));
        }
        assertEquals("completed", Db.str(tasks.get(taskId), "status"));

        Map<String, Object> answer = chat.ask(taskId, uid, "Why did you recommend this?");
        assertEquals("agents", answer.get("role"));
        assertTrue(answer.get("content").toString().contains("recommendation"));
        Map<String, Object> grounded = chat.ask(taskId, uid, "What about single sign-on and the security questionnaire?");
        assertTrue(grounded.get("content").toString().contains("[evidence") || grounded.get("content").toString().contains("[finding"),
                "retrieval answer should cite sources: " + grounded.get("content"));
        assertEquals(4, chat.history(taskId, uid).size());

        Map<String, Object> ins = insights.insights(uid);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) ins.get("totals");
        assertEquals(1L, totals.get("tasks"));
        assertEquals(2L, totals.get("followups"));
        assertFalse(((List<?>) ins.get("agents")).isEmpty());

        // Another user can't read the chat
        long other = ((Number) auth.register("oth" + System.nanoTime() % 100000, "secret123", "O").get("id")).longValue();
        assertThrows(ApiException.class, () -> chat.history(taskId, other));
    }

    @Autowired com.agentforge.workflow.Orchestrator orchestratorBean;

    private com.agentforge.workflow.Orchestrator orchestrator() {
        return orchestratorBean;
    }

    private static void signIn(long uid) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute(com.agentforge.auth.CurrentUser.SESSION_KEY, uid);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private String waitFor(long taskId, Set<String> states) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            String s = Db.str(tasks.get(taskId), "status");
            if (states.contains(s)) return s;
            Thread.sleep(100);
        }
        return Db.str(tasks.get(taskId), "status");
    }
}
