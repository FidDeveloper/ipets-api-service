package com.ipets;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Owner-scoped, idempotent upsert / soft-delete / incremental sync for every declared collection. */
@Service
class Store {
    private static final int SYNC_LIMIT = 5000;
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Collections collections;

    Store(JdbcClient db, ObjectMapper json, Collections collections) {
        this.db = db; this.json = json; this.collections = collections;
    }

    static void checkId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,36}")) throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_id", "Use an id of up to 36 letters, digits or dashes.");
    }

    private record Existing(String ownerId, boolean deleted) {}

    private Optional<Existing> lock(Spec.Collection c, String id) {
        return db.sql("SELECT owner_id, deleted_at IS NOT NULL FROM " + c.table() + " WHERE id = ? FOR UPDATE").param(id)
            .query((rs, n) -> new Existing(rs.getString(1), rs.getBoolean(2))).optional();
    }

    @Transactional
    public Map<String, Object> upsert(String owner, Spec.Collection c, String id, Map<String, Object> body) {
        String key = c.singleton() ? owner : id;
        checkId(key);
        var values = Spec.validate(c, body, json);
        String petId = null;
        if (c.petScoped()) {
            petId = body.get("petId") instanceof String s ? s : null;
            collections.requirePet(owner, petId);
        }
        collections.beforeWrite(owner, c, key, values);
        var existing = lock(c, key);
        if (existing.isPresent()) {
            if (!owner.equals(existing.get().ownerId())) throw notFound();
            if (existing.get().deleted()) throw new ApiError(HttpStatus.GONE, "deleted", "This item was deleted.");
            var sets = new StringJoiner(", ");
            var params = new ArrayList<Object>();
            values.forEach((column, value) -> { sets.add(column + " = ?"); params.add(value); });
            if (c.petScoped()) { sets.add("pet_id = ?"); params.add(petId); }
            params.add(key);
            db.sql("UPDATE " + c.table() + " SET " + sets + ", version = version + 1 WHERE id = ?").params(params).update();
        } else {
            var columns = new ArrayList<>(List.of("id", "owner_id"));
            var params = new ArrayList<Object>(List.of(key, owner));
            if (c.petScoped()) { columns.add("pet_id"); params.add(petId); }
            values.forEach((column, value) -> { columns.add(column); params.add(value); });
            try {
                db.sql("INSERT INTO " + c.table() + " (" + String.join(", ", columns) + ") VALUES (" + "?, ".repeat(columns.size() - 1) + "?)").params(params).update();
            } catch (DuplicateKeyException e) {
                throw new ApiError(HttpStatus.CONFLICT, "retry", "This item is being saved by another request; retry.");
            }
        }
        return read(c, key);
    }

    @Transactional
    public void delete(String owner, Spec.Collection c, String id) {
        String key = c.singleton() ? owner : id;
        checkId(key);
        var existing = lock(c, key);
        if (existing.isEmpty() || existing.get().deleted()) return;
        if (!owner.equals(existing.get().ownerId())) throw notFound();
        db.sql("UPDATE " + c.table() + " SET deleted_at = CURRENT_TIMESTAMP(3), version = version + 1 WHERE id = ?").param(key).update();
        collections.afterDelete(owner, c, key);
    }

    Map<String, Object> read(Spec.Collection c, String key) {
        return Spec.toJson(c, db.sql("SELECT * FROM " + c.table() + " WHERE id = ?").param(key).query().singleRow(), json);
    }

    /** All live items (no {@code since}) or every change, including deletions, after {@code since}. */
    public Map<String, Object> sync(String owner, String since) {
        Instant after = null;
        if (since != null && !since.isBlank()) {
            try { after = Instant.parse(since); } catch (DateTimeParseException e) {
                throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_since", "since must be an ISO-8601 instant.");
            }
        }
        String serverTime = Instant.now().toString();
        var out = new LinkedHashMap<String, Object>();
        for (Spec.Collection c : collections.all()) {
            var query = after == null
                ? db.sql("SELECT * FROM " + c.table() + " WHERE owner_id = ? AND deleted_at IS NULL ORDER BY updated_at LIMIT " + SYNC_LIMIT).param(owner)
                : db.sql("SELECT * FROM " + c.table() + " WHERE owner_id = ? AND updated_at > ? ORDER BY updated_at LIMIT " + SYNC_LIMIT).params(owner, Timestamp.from(after));
            out.put(c.name(), query.query().listOfRows().stream().map(row -> Spec.toJson(c, row, json)).toList());
        }
        return Map.of("serverTime", serverTime, "collections", out);
    }

    @Transactional
    public void deleteAll(String owner) {
        for (Spec.Collection c : collections.all()) db.sql("DELETE FROM " + c.table() + " WHERE owner_id = ?").param(owner).update();
        collections.onAccountDeleted(owner);
    }

    static ApiError notFound() {
        return new ApiError(HttpStatus.NOT_FOUND, "not_found", "Not found.");
    }
}
