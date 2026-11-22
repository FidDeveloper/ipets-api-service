package com.ipets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Private owner-scoped file storage on a volume. The type is detected from content, never trusted from the client. */
@Service
class FileStore {
    static final long MAX_BYTES = 20L * 1024 * 1024;
    record Stored(String id, String mime, long size, String sha256) {}

    private final JdbcClient db;
    private final Path root;

    FileStore(JdbcClient db, @Value("${ipets.file-root}") String root) throws IOException {
        this.db = db;
        this.root = Files.createDirectories(Path.of(root));
    }

    /** Detect JPEG, PNG, PDF or HEIC/HEIF from the first bytes; anything else is rejected. */
    static String sniff(byte[] head, int n) {
        if (n >= 3 && (head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xD8 && (head[2] & 0xff) == 0xFF) return "image/jpeg";
        if (n >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "image/png";
        if (n >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') return "application/pdf";
        if (n >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (brand.matches("heic|heix|hevc|heim|heis|mif1|msf1")) return "image/heic";
        }
        return null;
    }

    private Path path(String id) {
        Store.checkId(id);
        return root.resolve(id);
    }

    Optional<Stored> find(String owner, String id) {
        return db.sql("SELECT id, mime, size_bytes AS size, sha256 FROM files WHERE id = ? AND owner_id = ?").params(id, owner).query(Stored.class).optional();
    }

    boolean owns(String owner, String id) {
        return id != null && !id.isBlank() && find(owner, id).isPresent();
    }

    /** Store a stream under {@code id}. Re-uploading an existing id by the same owner returns the stored file (retry-safe). */
    Stored save(String owner, String id, InputStream in, String expectedSha256) {
        Store.checkId(id);
        var existingOwner = db.sql("SELECT owner_id FROM files WHERE id = ?").param(id).query(String.class).optional();
        if (existingOwner.isPresent()) {
            if (!existingOwner.get().equals(owner)) throw new ApiError(HttpStatus.CONFLICT, "file_id_taken", "Choose a different file id.");
            return find(owner, id).orElseThrow();
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(root, "upload-", ".part");
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] head = new byte[16];
            int headLength = 0;
            long total = 0;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    if (headLength < head.length) {
                        int copy = Math.min(head.length - headLength, read);
                        System.arraycopy(buffer, 0, head, headLength, copy);
                        headLength += copy;
                    }
                    total += read;
                    if (total > MAX_BYTES) throw new ApiError(HttpStatus.CONTENT_TOO_LARGE, "too_large", "Files must be 20 MB or smaller.");
                    out.write(buffer, 0, read);
                }
            }
            if (total == 0) throw new ApiError(HttpStatus.BAD_REQUEST, "empty_file", "The file is empty.");
            String mime = sniff(head, headLength);
            if (mime == null) throw new ApiError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_type", "Upload a JPEG, PNG, HEIC image or a PDF.");
            String sha = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(sha)) throw new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "checksum_mismatch", "The upload was corrupted; please retry.");
            Files.move(temp, path(id), StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            db.sql("INSERT INTO files (id, owner_id, mime, size_bytes, sha256) VALUES (?, ?, ?, ?, ?)").params(id, owner, mime, total, sha).update();
            return new Stored(id, mime, total, sha);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "storage_error", "The file could not be stored.");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    Path open(String owner, String id) {
        if (find(owner, id).isEmpty()) throw Store.notFound();
        return path(id);
    }

    /** Internal read for the OCR worker (ownership already enforced by the scan). */
    Path openInternal(String id) {
        return path(id);
    }

    void deleteAll(String owner) {
        for (String id : db.sql("SELECT id FROM files WHERE owner_id = ?").param(owner).query(String.class).list()) {
            try { Files.deleteIfExists(path(id)); } catch (IOException ignored) { }
        }
        db.sql("DELETE FROM files WHERE owner_id = ?").param(owner).update();
    }
}
