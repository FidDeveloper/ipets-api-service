package com.ipets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short-lived RS256 access tokens plus opaque, hashed, rotating refresh tokens with reuse detection. */
@Service
class Tokens {
    record Pair(String accessToken, String refreshToken, long expiresIn) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    Tokens(JdbcClient db, JwtEncoder encoder, @Value("${ipets.jwt.issuer}") String issuer, @Value("${ipets.jwt.audience}") String audience,
           @Value("${ipets.jwt.access-ttl}") Duration accessTtl, @Value("${ipets.jwt.refresh-ttl}") Duration refreshTtl) {
        this.db = db; this.encoder = encoder; this.issuer = issuer; this.audience = audience; this.accessTtl = accessTtl; this.refreshTtl = refreshTtl;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String access(String userId, String email) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(userId).issuedAt(now)
            .expiresAt(now.plus(accessTtl)).id(UUID.randomUUID().toString()).claim("email", email).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(() -> "RS256").build(), claims)).getTokenValue();
    }

    @Transactional
    Pair issue(String userId, String email, String familyId) {
        String refresh = randomToken();
        db.sql("INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)")
            .params(UUID.randomUUID().toString(), userId, familyId == null ? UUID.randomUUID().toString() : familyId, hash(refresh),
                Timestamp.from(Instant.now().plus(refreshTtl)))
            .update();
        return new Pair(access(userId, email), refresh, accessTtl.toSeconds());
    }

    record Stored(String id, String userId, String familyId, Timestamp expiresAt, Timestamp revokedAt, String email) {}

    /** Rotate atomically; presenting an already-rotated token revokes the whole family (likely theft). */
    @Transactional(noRollbackFor = ApiError.class)
    Pair refresh(String token) {
        if (token == null || token.isBlank()) throw invalid();
        var stored = db.sql("SELECT t.id, t.user_id, t.family_id, t.expires_at, t.revoked_at, u.email FROM refresh_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ? FOR UPDATE")
            .param(hash(token)).query(Stored.class).optional().orElseThrow(Tokens::invalid);
        if (stored.revokedAt() != null) {
            revokeFamily(stored.familyId());
            throw invalid();
        }
        if (stored.expiresAt().toInstant().isBefore(Instant.now())) throw invalid();
        db.sql("UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP(3) WHERE id = ?").param(stored.id()).update();
        return issue(stored.userId(), stored.email(), stored.familyId());
    }

    @Transactional
    void logout(String token) {
        if (token == null || token.isBlank()) return;
        db.sql("SELECT family_id FROM refresh_tokens WHERE token_hash = ?").param(hash(token)).query(String.class).optional().ifPresent(this::revokeFamily);
    }

    private void revokeFamily(String familyId) {
        db.sql("UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP(3)) WHERE family_id = ?").param(familyId).update();
    }

    private static ApiError invalid() {
        return new ApiError(HttpStatus.UNAUTHORIZED, "invalid_refresh_token", "Your session has expired. Please log in again.");
    }
}
