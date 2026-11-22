package com.ipets;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import static com.ipets.Spec.Field.*;

/** notification-service data: owner-confirmed care reminders. */
@Component
class Collections {
    static final Spec.Collection REMINDERS = new Spec.Collection("reminders", "reminders", true, false, List.of(
        text("title", "title", 200, true), date("date", "due_date", true), time("time", "due_time"), bool("done", "done"), text("recordId", "record_id", 36, false)));
    private final PetAccess pets;

    Collections(PetAccess pets) { this.pets = pets; }

    List<Spec.Collection> all() { return List.of(REMINDERS); }

    Spec.Collection get(String name) {
        return all().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(Store::notFound);
    }

    void requirePet(String owner, String petId) { pets.require(owner, petId); }

    void beforeWrite(String owner, Spec.Collection c, String id, Map<String, Object> values) {}

    void afterDelete(String owner, Spec.Collection c, String id) {}

    void onAccountDeleted(String owner) {}
}
