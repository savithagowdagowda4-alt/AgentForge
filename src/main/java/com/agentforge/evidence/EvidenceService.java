package com.agentforge.evidence;

import com.agentforge.agent.Models.EvidenceItem;
import com.agentforge.ai.AiService;
import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import com.agentforge.common.Json;
import com.agentforge.common.Text;
import com.agentforge.events.EventService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vision Intelligence Engine: stores uploaded evidence and extracts its content using
 * Tesseract OCR (if installed), Apache PDFBox, a DOCX reader and Claude vision (if configured).
 * When no extractor can handle a file, the evidence is stored with an explicit "unavailable" state.
 */
@Service
public class EvidenceService {
    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
    private static final Set<String> TEXT_EXT = Set.of("txt", "md", "csv", "json", "log", "tsv", "xml", "html");
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final Db db;
    private final AiService ai;
    private final EventService events;
    private final Path storage;
    private final String tesseractCmd;
    private final boolean tesseractAvailable;

    public EvidenceService(Db db, AiService ai, EventService events,
                           @Value("${agentforge.storage.dir}") String storageDir,
                           @Value("${agentforge.ocr.tesseract}") String tesseractCmd) throws IOException {
        this.db = db;
        this.ai = ai;
        this.events = events;
        this.storage = Path.of(storageDir).toAbsolutePath();
        Files.createDirectories(storage);
        this.tesseractCmd = tesseractCmd;
        this.tesseractAvailable = probeTesseract(tesseractCmd);
        log.info("OCR (Tesseract): {}", tesseractAvailable ? "available" : "not installed");
    }

    public boolean tesseractAvailable() {
        return tesseractAvailable;
    }

    public Map<String, Object> upload(long taskId, long uid, MultipartFile file, Integer version) throws IOException {
        if (file == null || file.isEmpty()) throw ApiException.bad("Empty file");
        return store(taskId, uid, file.getOriginalFilename(), file.getContentType(), file.getBytes(), version);
    }

    /** Adds a text document as evidence (used by scenario templates). */
    public Map<String, Object> addText(long taskId, long uid, String filename, String content, Integer version) throws IOException {
        return store(taskId, uid, filename, "text/plain", content.getBytes(StandardCharsets.UTF_8), version);
    }

    private Map<String, Object> store(long taskId, long uid, String filename, String contentType, byte[] bytes, Integer version) throws IOException {
        String original = Objects.requireNonNullElse(filename, "upload").replaceAll("[\\\\/:*?\"<>|]", "_");
        String type = detectType(original, contentType);
        Path dir = storage.resolve(uid + "/" + taskId);
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + "_" + original);
        Files.write(target, bytes);

        Extraction ex = extract(type, original, bytes);
        long id = db.insert("evidence", Db.row(
                "task_id", taskId, "user_id", uid, "filename", original, "content_type", type, "size_bytes", (long) bytes.length,
                "stored_path", target.toString(), "extracted_text", ex.text, "extraction_method", ex.method,
                "extraction_note", ex.note, "vision_summary", ex.summary));
        events.emit(taskId, version, "evidence_added", "inspector", null,
                "Evidence added: " + original + " (" + ex.method + ")", Map.of("evidenceId", id, "method", ex.method, "note", Objects.toString(ex.note, "")));
        return get(id, uid);
    }

    private record Extraction(String text, String summary, String method, String note) {
    }

    private Extraction extract(String type, String name, byte[] bytes) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        try {
            if (IMAGE_TYPES.contains(type)) return extractImage(type, ext, bytes);
            if ("application/pdf".equals(type)) return extractPdf(bytes);
            if ("docx".equals(ext)) return new Extraction(docxText(bytes), null, "docx-reader", null);
            if (type.startsWith("text/") || TEXT_EXT.contains(ext))
                return new Extraction(new String(bytes, StandardCharsets.UTF_8), null, "plain-text", null);
            return new Extraction("", null, "unsupported", "No extractor for this file type (" + type + "). Paste the relevant text in the correction box.");
        } catch (Exception e) {
            log.warn("Extraction failed for {}: {}", name, e.getMessage());
            return new Extraction("", null, "failed", "Extraction failed: " + e.getMessage());
        }
    }

    private Extraction extractImage(String type, String ext, byte[] bytes) throws Exception {
        String ocr = null;
        List<String> methods = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (tesseractAvailable) {
            ocr = runTesseract(bytes, ext.isEmpty() ? "png" : ext);
            methods.add("tesseract-ocr");
        } else {
            notes.add("Tesseract OCR is not installed");
        }
        String summary = null;
        if (ai.available()) {
            try {
                String raw = ai.complete(
                        "You extract evidence from images for a decision-analysis tool. Be literal and precise; never guess illegible text.",
                        "Analyse this image. Return JSON only: {\"type\": \"photo|screenshot|chart|diagram|document|other\", "
                                + "\"text\": \"all legible text, verbatim, preserving line breaks\", "
                                + "\"summary\": \"what the image shows. For charts: axes, series, trends and key values. For diagrams: components and relationships.\"}",
                        List.of(new AiService.Attachment(type, bytes)), 6000);
                JsonNode j = Json.extractObject(raw);
                summary = "[" + Json.text(j, "type", "image") + "] " + Json.text(j, "summary", "");
                if (ocr == null || ocr.isBlank()) ocr = Json.text(j, "text", "");
                methods.add("claude-vision");
            } catch (Exception e) {
                notes.add("Claude vision failed: " + e.getMessage());
            }
        } else {
            notes.add("AI vision is not configured");
        }
        if (methods.isEmpty())
            return new Extraction("", null, "unavailable", String.join("; ", notes) + ". Type the relevant content into the correction box.");
        return new Extraction(ocr == null ? "" : ocr.trim(), summary, String.join("+", methods), notes.isEmpty() ? null : String.join("; ", notes));
    }

    private Extraction extractPdf(byte[] bytes) throws Exception {
        String text;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            text = new PDFTextStripper().getText(doc);
        }
        if (text != null && text.trim().length() > 20) return new Extraction(text.trim(), null, "pdfbox", null);
        if (ai.available()) {
            String raw = ai.complete("You extract text from scanned documents. Be literal; never guess illegible text.",
                    "Transcribe this PDF. Return JSON only: {\"text\": \"verbatim text\", \"summary\": \"what the document is and its key points\"}",
                    List.of(new AiService.Attachment("application/pdf", bytes)), 12000);
            JsonNode j = Json.extractObject(raw);
            return new Extraction(Json.text(j, "text", ""), Json.text(j, "summary", null), "claude-document", "PDF had no text layer");
        }
        return new Extraction("", null, "unavailable", "PDF has no text layer (scanned) and AI vision is not configured.");
    }

    private String runTesseract(byte[] bytes, String ext) throws Exception {
        Path tmp = Files.createTempFile("af-ocr-", "." + ext);
        try {
            Files.write(tmp, bytes);
            Process p = new ProcessBuilder(tesseractCmd, tmp.toString(), "stdout").redirectErrorStream(false).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Tesseract timed out");
            }
            return new String(out, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean probeTesseract(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String docxText(byte[] bytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return xml.replaceAll("</w:p>", "\n").replaceAll("<[^>]+>", "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim();
                }
            }
        }
        return "";
    }

    private static String detectType(String name, String declared) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return declared == null ? "application/octet-stream" : declared;
    }

    public Map<String, Object> get(long id, long uid) {
        return db.one("SELECT id, task_id, filename, content_type, size_bytes, extracted_text, corrected_text, extraction_method, "
                        + "extraction_note, vision_summary, status, created_at FROM evidence WHERE id=? AND user_id=?", id, uid)
                .orElseThrow(() -> ApiException.notFound("Evidence"));
    }

    public List<Map<String, Object>> list(long taskId, long uid) {
        return db.list("SELECT id, task_id, filename, content_type, size_bytes, extracted_text, corrected_text, extraction_method, "
                + "extraction_note, vision_summary, status, created_at FROM evidence WHERE task_id=? AND user_id=? ORDER BY id", taskId, uid);
    }

    public Map<String, Object> correct(long id, long uid, String text, Integer version) {
        Map<String, Object> ev = get(id, uid);
        db.update("UPDATE evidence SET corrected_text=? WHERE id=? AND user_id=?", text, id, uid);
        events.emit(Db.lng(ev, "task_id"), version, "evidence_corrected", "user", "inspector",
                "User corrected extracted text of " + Db.str(ev, "filename"), Map.of("evidenceId", id));
        return get(id, uid);
    }

    public Map<String, Object> setStatus(long id, long uid, String status, String reason, Integer version) {
        if (!"active".equals(status) && !"rejected".equals(status)) throw ApiException.bad("Status must be active or rejected");
        Map<String, Object> ev = get(id, uid);
        db.update("UPDATE evidence SET status=? WHERE id=? AND user_id=?", status, id, uid);
        events.emit(Db.lng(ev, "task_id"), version, "rejected".equals(status) ? "evidence_rejected" : "evidence_restored", "user", "inspector",
                ("rejected".equals(status) ? "Evidence rejected: " : "Evidence restored: ") + Db.str(ev, "filename")
                        + (reason == null || reason.isBlank() ? "" : " — " + reason), Map.of("evidenceId", id));
        return get(id, uid);
    }

    public Path file(long id, long uid) {
        Map<String, Object> row = db.one("SELECT stored_path FROM evidence WHERE id=? AND user_id=?", id, uid)
                .orElseThrow(() -> ApiException.notFound("Evidence"));
        return Path.of(Db.str(row, "stored_path"));
    }

    public List<EvidenceItem> activeItems(long taskId) {
        List<EvidenceItem> out = new ArrayList<>();
        for (Map<String, Object> r : db.list("SELECT * FROM evidence WHERE task_id=? AND status='active' ORDER BY id", taskId)) {
            String corrected = Db.str(r, "corrected_text");
            String text = corrected != null && !corrected.isBlank() ? corrected : Objects.toString(r.get("extracted_text"), "");
            out.add(new EvidenceItem(Db.lng(r, "id"), Db.str(r, "filename"), Text.truncate(text, 20000), Db.str(r, "vision_summary")));
        }
        return out;
    }
}
