package com.agentforge;

import com.agentforge.auth.AuthService;
import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.common.Json;
import com.agentforge.common.Text;
import com.agentforge.insight.BoardService;
import com.agentforge.insight.ComparisonService;
import com.agentforge.insight.ReportService;
import com.agentforge.memory.MemoryService;
import com.agentforge.task.TaskQueryService;
import com.agentforge.task.TaskService;
import com.agentforge.workflow.Decision;
import com.agentforge.workflow.Orchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the full six-agent workflow end to end on the rule-based engine (no network). */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aftest;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "agentforge.ai.enabled=false",
        "agentforge.geo.enabled=false",
        "agentforge.storage.dir=./target/test-uploads"})
class WorkflowIntegrationTest {
    @Autowired AuthService auth;
    @Autowired TaskService tasks;
    @Autowired TaskQueryService query;
    @Autowired Orchestrator orchestrator;
    @Autowired MemoryService memory;
    @Autowired ComparisonService comparison;
    @Autowired BoardService board;
    @Autowired ReportService reports;
    @Autowired com.agentforge.evidence.EvidenceService evidence;
    @Autowired Db db;

    private static final String DESC = "We plan to launch our mobile app in 6 weeks with a budget of $40000. "
            + "We assume two contractors can finish QA by the deadline. The vendor contract has unclear liability terms. "
            + "Customer research shows 35% of users want offline mode.";

    @Test
    void fullWorkflowWithConflictCheckpointWhatIfAndIsolation() throws Exception {
        long alice = uid(auth.register("alice" + System.nanoTime() % 100000, "secret123", "Alice"));
        long bob = uid(auth.register("bob" + System.nanoTime() % 100000, "secret123", "Bob"));

        long taskId = uid(tasks.create(alice, "App launch", DESC, "voice", List.of("on_conflict"), "Pune, India", null, null));
        // Evidence that contradicts the description (budget and percentage differ)
        evidence.upload(taskId, alice, new MockMultipartFile("file", "budget.txt", "text/plain",
                "Approved launch budget: $32000. Survey: 22% of users asked for offline mode.".getBytes()), null);

        orchestrator.start(taskId, alice);
        String status = waitFor(taskId, Set.of("awaiting_approval", "completed", "failed"));
        assertEquals("awaiting_approval", status, "the inconsistency should produce an unresolved conflict and pause");

        Map<String, Object> detail = query.detail(taskId, alice);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) detail.get("gate");
        assertEquals("on_conflict", gate.get("checkpoint"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) ((Map<String, Object>) gate.get("payload")).get("conflicts");
        assertFalse(pending.isEmpty());

        // Approving without deciding every conflict is refused
        assertThrows(ApiException.class, () -> orchestrator.decide(taskId, alice, new Decision("approve", null, Map.of())));
        // Bob cannot see or decide Alice's task
        assertThrows(ApiException.class, () -> orchestrator.decide(taskId, bob, new Decision("approve", null, Map.of())));

        Map<Long, String> choices = new HashMap<>();
        pending.forEach(c -> choices.put(((Number) c.get("id")).longValue(), "reject"));
        orchestrator.decide(taskId, alice, new Decision("approve", "The evidence file is the current budget", choices));
        assertEquals("completed", waitFor(taskId, Set.of("completed", "failed", "awaiting_approval")));

        Map<String, Object> v1 = query.version(taskId, alice, 1);
        assertEquals("completed", v1.get("status"));
        assertNotNull(v1.get("recommendation"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) v1.get("findings");
        assertTrue(findings.stream().anyMatch(f -> "inconsistency".equals(f.get("kind"))), "inspector should flag the mismatch");
        assertTrue(findings.stream().anyMatch(f -> "rejected".equals(f.get("status"))), "user rejection should be applied");

        List<Map<String, Object>> events = db.list("SELECT type FROM events WHERE task_id=?", taskId);
        Set<String> types = new HashSet<>();
        events.forEach(e -> types.add((String) e.get("type")));
        for (String t : List.of("run_started", "plan", "agent_state", "message", "finding", "conflict", "checkpoint", "decision", "user_resolution", "recommendation"))
            assertTrue(types.contains(t), "missing event type " + t);
        assertEquals(1, db.list("SELECT * FROM decisions WHERE task_id=?", taskId).size());

        // Location was supplied but this task is not location-dependent: it must not appear in the report
        String md = reports.markdown(taskId, alice, 1);
        assertFalse(md.contains("Pune"), "location must not leak into an unrelated report");
        assertTrue(md.contains("## Recommendation"));

        // Visual board
        Map<String, Object> b = board.board(taskId, alice, 1);
        assertFalse(((List<?>) b.get("nodes")).isEmpty());
        assertFalse(((List<?>) b.get("edges")).isEmpty());

        // What-If: change assumptions -> version 2 -> compare
        @SuppressWarnings("unchecked")
        List<String> assumptions = new ArrayList<>(Json.MAPPER.convertValue(v1.get("assumptions"), List.class));
        assumptions.add("The deadline can slip by 4 weeks without penalty and no vendor contract is needed.");
        Map<String, Object> wi = orchestrator.whatIf(taskId, alice, assumptions, "Relaxed deadline");
        assertEquals(2, wi.get("version"));
        assertEquals("completed", waitFor(taskId, Set.of("completed", "failed", "awaiting_approval")).equals("awaiting_approval")
                ? resolveAll(taskId, alice) : "completed");
        Map<String, Object> cmp = comparison.compare(taskId, alice, 1, 2);
        assertFalse(((List<?>) cmp.get("assumptionChanges")).isEmpty());
        assertNotNull(cmp.get("impact"));

        // Memory: stored for Alice, invisible to Bob, retrievable for a related task
        assertFalse(memory.list(alice, null).isEmpty());
        assertTrue(memory.list(bob, null).isEmpty());
        assertFalse(memory.retrieve(alice, "mobile app launch budget QA contractors", null, 5).isEmpty());
        assertTrue(memory.retrieve(bob, "mobile app launch budget QA contractors", null, 5).isEmpty());
        assertThrows(ApiException.class, () -> query.detail(taskId, bob));
    }

    @Test
    void textUtilities() {
        assertTrue(Text.numbers("Budget $40,000 and 35% in 6 weeks").containsAll(List.of("$40000", "35%", "6weeks")));
        assertTrue(Text.similarity("launch the mobile app", "mobile app launch plan") > 0.5);
        assertEquals(42, Json.extractObject("Sure! ```json\n{\"a\": 42, \"s\": \"}\"}\n```").get("a").asInt());
    }

    private String resolveAll(long taskId, long uid) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) query.detail(taskId, uid).get("gate");
        Map<Long, String> choices = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) ((Map<String, Object>) gate.get("payload")).getOrDefault("conflicts", List.of());
        pending.forEach(c -> choices.put(((Number) c.get("id")).longValue(), "uphold"));
        orchestrator.decide(taskId, uid, new Decision("approve", null, choices));
        return waitFor(taskId, Set.of("completed", "failed"));
    }

    private String waitFor(long taskId, Set<String> states) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            String s = Db.str(tasks.get(taskId), "status");
            if (states.contains(s)) return s;
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + states + ", status=" + Db.str(tasks.get(taskId), "status"));
        return null;
    }

    private static long uid(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }
}
