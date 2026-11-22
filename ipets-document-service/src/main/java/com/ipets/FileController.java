package com.ipets;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class FileController {
    private final FileStore files;

    FileController(FileStore files) { this.files = files; }

    /** Raw-body upload. Clients may send {@code X-File-Id} (their own UUID) so retries never duplicate. */
    @PostMapping("/api/v1/files")
    @ResponseStatus(HttpStatus.CREATED)
    FileStore.Stored upload(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-File-Id", required = false) String id, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > FileStore.MAX_BYTES) throw new ApiError(HttpStatus.CONTENT_TOO_LARGE, "too_large", "Files must be 20 MB or smaller.");
        return files.save(jwt.getSubject(), id == null || id.isBlank() ? UUID.randomUUID().toString() : id, request.getInputStream(), null);
    }

    @GetMapping("/api/v1/files/{id}")
    ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        var meta = files.find(jwt.getSubject(), id).orElseThrow(Store::notFound);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(meta.mime())).contentLength(meta.size())
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400").header("X-Content-Type-Options", "nosniff")
            .body(new FileSystemResource(files.open(jwt.getSubject(), id)));
    }
}
