package com.ipets;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import static com.ipets.Spec.Field.*;

/** document-service data: stored documents and in-progress scan review drafts. */
@Component
class Collections {
    static final Spec.Collection DOCUMENTS = new Spec.Collection("documents", "documents", true, false, List.of(
        text("name", "name", 200, true), text("mime", "mime", 100, true), date("date", "doc_date", true), choice("status", "status", "Needs review", "Saved"),
        bool("sample", "sample"), text("fileId", "file_id", 36, true)));
    static final Spec.Collection DRAFTS = new Spec.Collection("drafts", "drafts", true, false, List.of(
        json("documentIds", "document_ids", 500), date("created", "created", true), choice("engine", "engine", "demo", "fast", "escalated"),
        text("cardPetName", "card_pet_name", 200, false), json("warnings", "warnings", 20000), json("rows", "draft_rows", 200000)));
    private final PetAccess pets;
    private final FileStore files;
    private final JdbcClient db;

    Collections(PetAccess pets, FileStore files, JdbcClient db) { this.pets = pets; this.files = files; this.db = db; }

    List<Spec.Collection> all() { return List.of(DOCUMENTS, DRAFTS); }

    Spec.Collection get(String name) {
        return all().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(Store::notFound);
    }

    void requirePet(String owner, String petId) { pets.require(owner, petId); }

    void beforeWrite(String owner, Spec.Collection c, String id, Map<String, Object> values) {
        if (c == DOCUMENTS && !files.owns(owner, (String) values.get("file_id")))
            throw new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "file_not_found", "Upload the file before saving the document.");
    }

    void afterDelete(String owner, Spec.Collection c, String id) {}

    void onAccountDeleted(String owner) {
        db.sql("DELETE FROM scans WHERE owner_id = ?").param(owner).update();
        files.deleteAll(owner);
    }
}
