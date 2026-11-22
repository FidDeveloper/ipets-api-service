package com.ipets;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Scan-to-record pipeline (plan 10.9): session → per-page upload with checksum → complete → OCR job on RabbitMQ
 * → worker stage events and result → owner reviews the draft. The scan row is the job's source of truth.
 */
@RestController
class Scans {
    static final Set<String> STAGES = Set.of("UPLOADED", "VALIDATING", "SCANNED", "OCR_STARTED", "OCR_DONE", "STRUCTURING", "ESCALATED", "VALIDATED", "NEEDS_REVIEW");
    static final int MAX_PAGES = 10;
    static final int MAX_ATTEMPTS = 3;

    record PageSpec(int page, String contentType, long size, String sha256) {}
    record SessionRequest(List<PageSpec> pages) {}
    record Upload(int page, String url) {}
    record Session(String scanId, List<Upload> uploads) {}
    record Status(String status, String stage, String message) {}
    record Event(String stage) {}

    private final JdbcClient db;
    private final FileStore files;
    private final PetAccess pets;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String queue;
    private final String internalToken;

    Scans(JdbcClient db, FileStore files, PetAccess pets, RabbitTemplate rabbit, ObjectMapper json,
          @Value("${ipets.ocr-queue}") String queue, @Value("${ipets.internal-token}") String internalToken) {
        this.db = db; this.files = files; this.pets = pets; this.rabbit = rabbit; this.json = json; this.queue = queue; this.internalToken = internalToken;
    }

    @Bean
    static Queue ocrQueue(@Value("${ipets.ocr-queue}") String name) {
        return QueueBuilder.durable(name).withArgument("x-dead-letter-exchange", "").withArgument("x-dead-letter-routing-key", name + ".dead").build();
    }

    @Bean
    static Queue ocrDeadLetters(@Value("${ipets.ocr-queue}") String name) {
        return QueueBuilder.durable(name + ".dead").build();
    }

    private record Owned(String ownerId, String status) {}

    private Owned scan(String owner, String id) {
        Store.checkId(id);
        var row = db.sql("SELECT owner_id, status FROM scans WHERE id = ?").param(id).query(Owned.class).optional().orElseThrow(Store::notFound);
        if (owner != null && !row.ownerId().equals(owner)) throw Store.notFound();
        return row;
    }

    @PostMapping("/api/v1/pets/{petId}/scans")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Session create(@AuthenticationPrincipal Jwt jwt, @PathVariable String petId, @RequestBody SessionRequest body) {
        pets.require(jwt.getSubject(), petId);
        var pages = body.pages() == null ? List.<PageSpec>of() : body.pages();
        if (pages.isEmpty() || pages.size() > MAX_PAGES) throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_pages", "Send 1 to 10 pages.");
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO scans (id, owner_id, pet_id, status, stage) VALUES (?, ?, ?, 'UPLOADING', 'UPLOADED')").params(id, jwt.getSubject(), petId).update();
        var uploads = new ArrayList<Upload>();
        for (int i = 0; i < pages.size(); i++) {
            var p = pages.get(i);
            if (p.size() <= 0 || p.size() > FileStore.MAX_BYTES) throw new ApiError(HttpStatus.CONTENT_TOO_LARGE, "too_large", "Each page must be 20 MB or smaller.");
            if (p.sha256() == null || !p.sha256().matches("[0-9a-fA-F]{64}")) throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_checksum", "Each page needs a SHA-256 checksum.");
            db.sql("INSERT INTO scan_pages (scan_id, page_no, file_id, mime, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, i + 1, UUID.randomUUID().toString(), String.valueOf(p.contentType()).substring(0, Math.min(40, String.valueOf(p.contentType()).length())), p.size(), p.sha256().toLowerCase()).update();
            uploads.add(new Upload(i + 1, "/document/api/v1/scans/" + id + "/pages/" + (i + 1)));
        }
        return new Session(id, uploads);
    }

    private record PageRow(String fileId, long sizeBytes, String sha256, boolean uploaded) {}

    @PutMapping("/api/v1/scans/{id}/pages/{page}")
    Map<String, Object> uploadPage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable int page, HttpServletRequest request) throws IOException {
        var s = scan(jwt.getSubject(), id);
        if (!s.status().equals("UPLOADING")) throw new ApiError(HttpStatus.CONFLICT, "upload_closed", "This scan is no longer accepting pages.");
        var row = db.sql("SELECT file_id, size_bytes, sha256, uploaded FROM scan_pages WHERE scan_id = ? AND page_no = ?").params(id, page).query(PageRow.class).optional().orElseThrow(Store::notFound);
        if (request.getContentLengthLong() > 0 && request.getContentLengthLong() != row.sizeBytes())
            throw new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "size_mismatch", "The page size does not match the session.");
        var stored = files.save(jwt.getSubject(), row.fileId(), request.getInputStream(), row.sha256());
        db.sql("UPDATE scan_pages SET uploaded = TRUE, mime = ? WHERE scan_id = ? AND page_no = ?").params(stored.mime(), id, page).update();
        return Map.of("page", page, "fileId", stored.id());
    }

    /** Idempotent: completing an already-queued scan returns its current status. */
    @PostMapping("/api/v1/scans/{id}/complete")
    @Transactional
    Status complete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        var s = scan(jwt.getSubject(), id);
        if (s.status().equals("UPLOADING")) {
            int missing = db.sql("SELECT COUNT(*) FROM scan_pages WHERE scan_id = ? AND uploaded = FALSE").param(id).query(Integer.class).single();
            if (missing > 0) throw new ApiError(HttpStatus.CONFLICT, "pages_missing", "Upload every page before completing.");
            db.sql("UPDATE scans SET status = 'QUEUED', stage = 'SCANNED' WHERE id = ?").param(id).update();
            publish(id);
        }
        return status(jwt, id);
    }

    void publish(String id) {
        var pages = db.sql("SELECT page_no, file_id, mime FROM scan_pages WHERE scan_id = ? ORDER BY page_no").param(id).query().listOfRows();
        var message = Map.of("scanId", id, "pages", pages.stream().map(p -> Map.of("page", p.get("page_no"), "fileId", p.get("file_id"), "mime", p.get("mime"))).toList());
        db.sql("UPDATE scans SET attempts = attempts + 1 WHERE id = ?").param(id).update();
        rabbit.convertAndSend(queue, json.writeValueAsString(message));
    }

    @GetMapping("/api/v1/scans/{id}")
    Status status(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        scan(jwt.getSubject(), id);
        return db.sql("SELECT status, stage, message FROM scans WHERE id = ?").param(id).query(Status.class).single();
    }

    @GetMapping("/api/v1/scans/{id}/draft")
    Map<String, Object> draft(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        var s = scan(jwt.getSubject(), id);
        if (!s.status().equals("NEEDS_REVIEW")) throw new ApiError(HttpStatus.CONFLICT, "not_ready", "The draft is not ready yet.");
        String raw = db.sql("SELECT draft FROM scans WHERE id = ?").param(id).query(String.class).single();
        @SuppressWarnings("unchecked") Map<String, Object> draft = new LinkedHashMap<>((Map<String, Object>) json.readValue(raw, Map.class));
        draft.put("scanId", id);
        draft.put("pages", db.sql("SELECT page_no AS page, file_id AS fileId, mime FROM scan_pages WHERE scan_id = ? ORDER BY page_no").param(id).query().listOfRows());
        return draft;
    }

    // ---- Internal endpoints for the OCR worker (blocked at the public edge, require the internal token) ----

    @GetMapping("/internal/scans/{id}/pages/{page}")
    ResponseEntity<Resource> internalPage(@RequestHeader(value = "X-Internal-Token", required = false) String token, @PathVariable String id, @PathVariable int page) {
        SecurityConfig.requireInternal(internalToken, token);
        scan(null, id);
        var map = db.sql("SELECT file_id, mime FROM scan_pages WHERE scan_id = ? AND page_no = ? AND uploaded = TRUE").params(id, page).query().listOfRows();
        if (map.isEmpty()) throw Store.notFound();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) map.get(0).get("mime")))
            .body(new FileSystemResource(files.openInternal((String) map.get(0).get("file_id"))));
    }

    @PostMapping("/internal/scans/{id}/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void event(@RequestHeader(value = "X-Internal-Token", required = false) String token, @PathVariable String id, @RequestBody Event body) {
        SecurityConfig.requireInternal(internalToken, token);
        var s = scan(null, id);
        if (!STAGES.contains(body.stage())) throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_stage", "Unknown stage.");
        if (s.status().equals("QUEUED") || s.status().equals("PROCESSING"))
            db.sql("UPDATE scans SET status = 'PROCESSING', stage = ? WHERE id = ?").params(body.stage(), id).update();
    }

    /** Accepts {@code {"draft": {...}}} or {@code {"error": "..."}}; late results for finished scans are ignored. */
    @PostMapping("/internal/scans/{id}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void result(@RequestHeader(value = "X-Internal-Token", required = false) String token, @PathVariable String id, @RequestBody Map<String, Object> body) {
        SecurityConfig.requireInternal(internalToken, token);
        var s = scan(null, id);
        if (!s.status().equals("QUEUED") && !s.status().equals("PROCESSING")) return;
        if (body.get("error") instanceof String error) {
            db.sql("UPDATE scans SET status = 'FAILED', message = ? WHERE id = ?").params(error.substring(0, Math.min(300, error.length())), id).update();
            return;
        }
        if (!(body.get("draft") instanceof Map<?, ?> draft) || !(draft.get("rows") instanceof List<?> rows) || rows.size() > 30)
            throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_draft", "The draft must contain at most 30 rows.");
        String raw = json.writeValueAsString(draft);
        if (raw.length() > 500_000) throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_draft", "The draft is too large.");
        db.sql("UPDATE scans SET status = 'NEEDS_REVIEW', stage = 'NEEDS_REVIEW', draft = ? WHERE id = ?").params(raw, id).update();
    }

    /** Recover lost broker messages and stalled workers without double-processing finished scans. */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    void watchdog() {
        for (String id : db.sql("SELECT id FROM scans WHERE status = 'QUEUED' AND updated_at < NOW(3) - INTERVAL 60 SECOND AND attempts < ?").param(MAX_ATTEMPTS).query(String.class).list()) {
            try { publish(id); } catch (RuntimeException ignored) { }
        }
        db.sql("UPDATE scans SET status = 'FAILED', message = 'Processing timed out. Please retry or enter the record manually.' WHERE (status = 'PROCESSING' AND updated_at < NOW(3) - INTERVAL 5 MINUTE) OR (status = 'QUEUED' AND attempts >= ? AND updated_at < NOW(3) - INTERVAL 5 MINUTE)").param(MAX_ATTEMPTS).update();
        db.sql("DELETE FROM scans WHERE status = 'UPLOADING' AND created_at < NOW(3) - INTERVAL 1 DAY").update();
    }
}
