package com.ipets;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Accounts {
    record User(String id, String email, String name) {}
    private record Row(String id, String email, String name, String passwordHash) {}

    private static final int MAX_FAILURES = 10;
    private static final long WINDOW_SECONDS = 15 * 60;
    // A hash of a random value so unknown emails cost the same as wrong passwords.
    private final String dummyHash;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final JdbcClient db;
    private final PasswordEncoder encoder;

    Accounts(JdbcClient db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
        this.dummyHash = encoder.encode(Tokens.randomToken());
    }

    @Transactional
    User register(String name, String email, String password) {
        var user = new User(UUID.randomUUID().toString(), Policy.email(email), Policy.name(name));
        String hash = encoder.encode(Policy.password(password));
        try {
            db.sql("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, ?, ?)").params(user.id(), user.email(), user.name(), hash).update();
        } catch (DuplicateKeyException e) {
            throw new ApiError(HttpStatus.CONFLICT, "email_taken", "An account with this email already exists.");
        }
        return user;
    }

    User login(String rawEmail, String password) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        throttle(email);
        Optional<Row> row = db.sql("SELECT id, email, name, password_hash FROM users WHERE email = ?").param(email).query(Row.class).optional();
        boolean ok = encoder.matches(password == null ? "" : password, row.map(Row::passwordHash).orElse(dummyHash)) && row.isPresent();
        if (!ok) {
            failures.computeIfAbsent(email, k -> new ArrayDeque<>()).add(Instant.now());
            throw new ApiError(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect.");
        }
        failures.remove(email);
        return new User(row.get().id(), row.get().email(), row.get().name());
    }

    private void throttle(String email) {
        Deque<Instant> attempts = failures.get(email);
        if (attempts == null) return;
        synchronized (attempts) {
            Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) attempts.pollFirst();
            if (attempts.size() >= MAX_FAILURES) throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "Too many failed attempts. Try again in 15 minutes.");
        }
    }

    User get(String id) {
        return db.sql("SELECT id, email, name FROM users WHERE id = ?").param(id).query(User.class).optional()
            .orElseThrow(() -> new ApiError(HttpStatus.UNAUTHORIZED, "account_missing", "This account no longer exists."));
    }

    User rename(String id, String name) {
        db.sql("UPDATE users SET name = ? WHERE id = ?").params(Policy.name(name), id).update();
        return get(id);
    }

    @Transactional
    void delete(String id) {
        db.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }

    @Transactional
    void ensureDemo(String email, String name, String password) {
        if (db.sql("SELECT COUNT(*) FROM users WHERE email = ?").param(email).query(Integer.class).single() == 0)
            db.sql("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, ?, ?)").params(UUID.randomUUID().toString(), email, name, encoder.encode(password)).update();
    }
}
