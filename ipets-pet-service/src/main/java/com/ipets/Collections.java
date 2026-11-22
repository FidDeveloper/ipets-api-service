package com.ipets;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import static com.ipets.Spec.Field.*;

/** pet-service data: profile, pets and pet-scoped care/lifestyle records. Owns the pet-access decision. */
@Component
@RestController
class Collections {
    static final int STARTING_POINTS = 500;
    static final Spec.Collection PETS = new Spec.Collection("pets", "pets", false, false, List.of(
        text("name", "name", 200, true), choice("species", "species", "Cat", "Dog", "Rabbit", "Bird", "Other"),
        text("breed", "breed", 200, true), choice("sex", "sex", "Female", "Male", "Unknown"), date("birthday", "birthday", true),
        decimal("weight", "weight", 0, 1000), text("color", "color", 100, false), text("microchip", "microchip", 100, false),
        bool("sterilized", "sterilized"), text("photoFileId", "photo_file_id", 36, false)));
    static final List<Spec.Collection> ALL = List.of(
        new Spec.Collection("profile", "profiles", false, true, List.of(text("name", "name", 100, true), text("phone", "phone", 30, false),
            bool("notifications", "notifications"), bool("marketing", "marketing"), choice("language", "language", "en", "ms"))),
        PETS,
        new Spec.Collection("records", "medical_records", true, false, List.of(text("title", "title", 200, true),
            choice("kind", "kind", "Vaccination", "Checkup", "Treatment", "Other"), date("date", "event_date", true), text("clinic", "clinic", 200, true),
            text("notes", "notes", 3000, false), date("dueDate", "due_date", false), choice("source", "source", "manual", "card"), text("documentId", "document_id", 36, false))),
        new Spec.Collection("feeds", "feeds", true, false, List.of(text("food", "food", 200, true), decimal("grams", "grams", 0, 10000),
            date("date", "feed_date", true), text("notes", "notes", 2000, false))),
        new Spec.Collection("journals", "journals", true, false, List.of(text("title", "title", 200, true), text("body", "body", 5000, false), date("date", "entry_date", true))),
        new Spec.Collection("memories", "memories", true, false, List.of(text("caption", "caption", 200, true), text("fileId", "file_id", 36, true), date("date", "memory_date", true))),
        new Spec.Collection("bookings", "bookings", true, false, List.of(text("provider", "provider", 200, true), text("service", "service", 200, true),
            date("date", "booking_date", true), text("time", "booking_time", 20, true), text("notes", "notes", 2000, false), choice("status", "status", "Reserved", "Cancelled"))),
        new Spec.Collection("redemptions", "redemptions", false, false, List.of(text("title", "title", 200, true), integer("cost", "cost", 1, STARTING_POINTS))));
    private static final List<String> PET_CHILDREN = List.of("medical_records", "feeds", "journals", "memories", "bookings");

    private final JdbcClient db;
    private final String internalToken;

    Collections(JdbcClient db, @Value("${ipets.internal-token}") String internalToken) {
        this.db = db; this.internalToken = internalToken;
    }

    List<Spec.Collection> all() { return ALL; }

    Spec.Collection get(String name) {
        return ALL.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(Store::notFound);
    }

    boolean ownsPet(String owner, String petId) {
        return petId != null && db.sql("SELECT COUNT(*) FROM pets WHERE id = ? AND owner_id = ? AND deleted_at IS NULL").params(petId, owner).query(Integer.class).single() > 0;
    }

    void requirePet(String owner, String petId) {
        if (!ownsPet(owner, petId)) throw new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "pet_not_found", "Choose one of your pets.");
    }

    void beforeWrite(String owner, Spec.Collection c, String id, Map<String, Object> values) {
        if (c.name().equals("redemptions")) {
            int spent = db.sql("SELECT COALESCE(SUM(cost), 0) FROM redemptions WHERE owner_id = ? AND deleted_at IS NULL AND id <> ?").params(owner, id).query(Integer.class).single();
            if (spent + (Integer) values.get("cost") > STARTING_POINTS) throw new ApiError(HttpStatus.CONFLICT, "insufficient_points", "Not enough points.");
        }
    }

    void afterDelete(String owner, Spec.Collection c, String id) {
        if (c == PETS) for (String table : PET_CHILDREN)
            db.sql("UPDATE " + table + " SET deleted_at = CURRENT_TIMESTAMP(3), version = version + 1 WHERE pet_id = ? AND owner_id = ? AND deleted_at IS NULL").params(id, owner).update();
    }

    /** Used by notification/document services before writing pet-scoped data. */
    @GetMapping("/internal/pets/{petId}/owners/{ownerId}")
    Map<String, Boolean> internalOwns(@RequestHeader(value = "X-Internal-Token", required = false) String token, @PathVariable String petId, @PathVariable String ownerId) {
        SecurityConfig.requireInternal(internalToken, token);
        return Map.of("owned", ownsPet(ownerId, petId));
    }

    void onAccountDeleted(String owner) {}
}
