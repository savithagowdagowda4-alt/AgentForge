package com.agentforge.web;

import com.agentforge.ai.AiService;
import com.agentforge.auth.CurrentUser;
import com.agentforge.common.Db;
import com.agentforge.events.EventService;
import com.agentforge.evidence.EvidenceService;
import com.agentforge.geo.GeoService;
import com.agentforge.memory.MemoryService;
import com.agentforge.task.TaskService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Evidence, memory, notifications, search, geo and status endpoints. */
public final class SupportControllers {
    private SupportControllers() {
    }

    @RestController
    @RequestMapping("/api/evidence")
    public static class EvidenceController {
        private final CurrentUser user;
        private final EvidenceService evidence;
        private final TaskService tasks;

        public EvidenceController(CurrentUser user, EvidenceService evidence, TaskService tasks) {
            this.user = user;
            this.evidence = evidence;
            this.tasks = tasks;
        }

        @GetMapping("/{id}")
        public Map<String, Object> get(@PathVariable long id) {
            return evidence.get(id, user.id());
        }

        @GetMapping("/{id}/file")
        public ResponseEntity<FileSystemResource> file(@PathVariable long id) throws Exception {
            Map<String, Object> ev = evidence.get(id, user.id());
            Path p = evidence.file(id, user.id());
            if (!Files.exists(p)) return ResponseEntity.notFound().build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + Db.str(ev, "filename").replace("\"", "") + "\"")
                    .contentType(MediaType.parseMediaType(Objects.toString(ev.get("content_type"), "application/octet-stream")))
                    .body(new FileSystemResource(p));
        }

        @PatchMapping("/{id}")
        public Map<String, Object> correct(@PathVariable long id, @RequestBody Map<String, String> body) {
            return evidence.correct(id, user.id(), body.get("text"), version(id));
        }

        @PostMapping("/{id}/status")
        public Map<String, Object> status(@PathVariable long id, @RequestBody Map<String, String> body) {
            return evidence.setStatus(id, user.id(), body.get("status"), body.get("reason"), version(id));
        }

        private Integer version(long evidenceId) {
            long taskId = Db.lng(evidence.get(evidenceId, user.id()), "task_id");
            int v = ((Number) tasks.owned(taskId, user.id()).get("current_version")).intValue();
            return v == 0 ? null : v;
        }
    }

    @RestController
    @RequestMapping("/api/memory")
    public static class MemoryController {
        private final CurrentUser user;
        private final MemoryService memory;

        public MemoryController(CurrentUser user, MemoryService memory) {
            this.user = user;
            this.memory = memory;
        }

        @GetMapping
        public List<Map<String, Object>> list(@RequestParam(required = false) String q) {
            return memory.list(user.id(), q);
        }

        @PostMapping
        public Map<String, Object> create(@RequestBody Map<String, String> body) {
            return memory.save(user.id(), null, Objects.requireNonNullElse(body.get("kind"), "note"), body.get("content"), body.get("tags"), "user note");
        }

        @PatchMapping("/{id}")
        public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, String> body) {
            return memory.update(id, user.id(), body.get("content"), body.get("tags"));
        }

        @DeleteMapping("/{id}")
        public Map<String, Object> delete(@PathVariable long id) {
            memory.delete(id, user.id());
            return Map.of("ok", true);
        }
    }

    @RestController
    @RequestMapping("/api")
    public static class MiscController {
        private final CurrentUser user;
        private final Db db;
        private final EventService events;
        private final AiService ai;
        private final EvidenceService evidence;
        private final GeoService geo;

        public MiscController(CurrentUser user, Db db, EventService events, AiService ai, EvidenceService evidence, GeoService geo) {
            this.user = user;
            this.db = db;
            this.events = events;
            this.ai = ai;
            this.evidence = evidence;
            this.geo = geo;
        }

        @GetMapping("/status")
        public Map<String, Object> status() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("ai", Map.of("available", ai.available(), "model", ai.model(),
                    "message", ai.available() ? "Claude is connected" : "Set ANTHROPIC_API_KEY and restart to enable AI reasoning and vision. Agents run in rule-based mode."));
            s.put("ocr", Map.of("available", evidence.tesseractAvailable(),
                    "message", evidence.tesseractAvailable() ? "Tesseract OCR installed" : "Tesseract not installed" + (ai.available() ? "; Claude vision is used for image text" : "")));
            s.put("vision", Map.of("available", ai.available()));
            s.put("pdf", Map.of("available", true, "message", "Apache PDFBox"));
            s.put("geo", Map.of("available", geo.enabled(), "message", geo.enabled() ? "OpenStreetMap Nominatim" : "Disabled"));
            s.put("backend", "Java " + Runtime.version() + " / Spring Boot");
            return s;
        }

        @GetMapping("/notifications")
        public List<Map<String, Object>> notifications() {
            return db.list("SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 50", user.id());
        }

        @PostMapping("/notifications/read")
        public Map<String, Object> readAll() {
            db.update("UPDATE notifications SET is_read=TRUE WHERE user_id=?", user.id());
            return Map.of("ok", true);
        }

        @PostMapping("/notifications/{id}/read")
        public Map<String, Object> read(@PathVariable long id) {
            db.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?", id, user.id());
            return Map.of("ok", true);
        }

        @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter stream() {
            return events.subscribeUser(user.id());
        }

        @GetMapping("/search")
        public Map<String, Object> search(@RequestParam String q) {
            long uid = user.id();
            String like = "%" + q.toLowerCase(Locale.ROOT).trim() + "%";
            Map<String, Object> out = new LinkedHashMap<>();
            if (q.isBlank()) return Map.of("tasks", List.of(), "findings", List.of(), "memories", List.of(), "evidence", List.of());
            out.put("tasks", db.list("SELECT id, title, status, updated_at FROM tasks WHERE user_id=? AND (LOWER(title) LIKE ? OR LOWER(description) LIKE ?) "
                    + "ORDER BY updated_at DESC LIMIT 20", uid, like, like));
            out.put("findings", db.list("SELECT f.id, f.task_id, f.version_no, f.agent, f.kind, f.title, t.title AS task_title FROM findings f "
                    + "JOIN tasks t ON t.id=f.task_id WHERE t.user_id=? AND f.status<>'superseded' AND (LOWER(f.title) LIKE ? OR LOWER(f.detail) LIKE ?) "
                    + "ORDER BY f.id DESC LIMIT 20", uid, like, like));
            out.put("memories", db.list("SELECT id, kind, content, task_id FROM memories WHERE user_id=? AND (LOWER(content) LIKE ? OR LOWER(tags) LIKE ?) "
                    + "ORDER BY id DESC LIMIT 20", uid, like, like));
            out.put("evidence", db.list("SELECT e.id, e.task_id, e.filename, t.title AS task_title FROM evidence e JOIN tasks t ON t.id=e.task_id "
                    + "WHERE e.user_id=? AND (LOWER(e.filename) LIKE ? OR LOWER(e.extracted_text) LIKE ? OR LOWER(e.corrected_text) LIKE ?) "
                    + "ORDER BY e.id DESC LIMIT 20", uid, like, like, like));
            return out;
        }

        @GetMapping("/geo/reverse")
        public Map<String, Object> reverse(@RequestParam double lat, @RequestParam double lon) {
            user.id();
            return geo.reverse(lat, lon).<Map<String, Object>>map(n -> Map.of("available", true, "name", n))
                    .orElse(Map.of("available", false, "message", "Reverse geocoding unavailable"));
        }

        @GetMapping("/geo/geocode")
        public Map<String, Object> geocode(@RequestParam String q) {
            user.id();
            return geo.geocode(q).<Map<String, Object>>map(p -> Map.of("available", true, "name", p.name(), "lat", p.lat(), "lon", p.lon()))
                    .orElse(Map.of("available", false, "message", "Location not found or geocoding unavailable"));
        }

        @GetMapping("/stats")
        public Map<String, Object> stats() {
            long uid = user.id();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("tasks", count("SELECT COUNT(*) AS n FROM tasks WHERE user_id=?", uid));
            s.put("completed", count("SELECT COUNT(*) AS n FROM tasks WHERE user_id=? AND status='completed'", uid));
            s.put("awaiting", count("SELECT COUNT(*) AS n FROM tasks WHERE user_id=? AND status='awaiting_approval'", uid));
            s.put("findings", count("SELECT COUNT(*) AS n FROM findings f JOIN tasks t ON t.id=f.task_id WHERE t.user_id=?", uid));
            s.put("conflicts", count("SELECT COUNT(*) AS n FROM conflicts c JOIN tasks t ON t.id=c.task_id WHERE t.user_id=?", uid));
            s.put("unresolved", count("SELECT COUNT(*) AS n FROM conflicts c JOIN tasks t ON t.id=c.task_id WHERE t.user_id=? AND c.status='unresolved' AND c.version_no=t.current_version", uid));
            s.put("memories", count("SELECT COUNT(*) AS n FROM memories WHERE user_id=?", uid));
            s.put("evidence", count("SELECT COUNT(*) AS n FROM evidence WHERE user_id=?", uid));
            return s;
        }

        @GetMapping("/conflicts")
        public List<Map<String, Object>> conflicts() {
            return db.list("SELECT c.*, f.title AS finding_title, t.title AS task_title, t.status AS task_status FROM conflicts c "
                    + "JOIN tasks t ON t.id=c.task_id LEFT JOIN findings f ON f.id=c.finding_id "
                    + "WHERE t.user_id=? AND c.version_no=t.current_version ORDER BY c.id DESC", user.id());
        }

        private long count(String sql, long uid) {
            return db.one(sql, uid).map(r -> ((Number) r.get("n")).longValue()).orElse(0L);
        }
    }
}
