package com.ipets;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ReminderTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private Map<String, Object> reminder() {
        return new HashMap<>(Map.of("title", "Rabies booster", "date", "2026-12-01", "time", "09:00"));
    }

    @Test void validReminder() {
        var values = Spec.validate(Collections.REMINDERS, reminder(), json);
        assertEquals("09:00", values.get("due_time"));
        assertEquals(false, values.get("done"));
    }

    @Test void rejectsBadTimeAndDate() {
        for (var change : java.util.List.<Map<String, Object>>of(Map.of("time", "9am"), Map.of("time", "24:00"), Map.of("date", "2026-13-01"), Map.of("title", ""))) {
            var body = reminder(); body.putAll(change);
            assertThrows(ApiError.class, () -> Spec.validate(Collections.REMINDERS, body, json), change.toString());
        }
    }

    @Test void rejectsEveryTokenWithoutKeyConfiguration() {
        assertThrows(BadJwtException.class, () -> new SecurityConfig().decoder("https://ipets.test", "", "ipets-api").decode("forged"));
    }

    @Test void malformedPetIdIsRejectedWithoutNetwork() {
        assertThrows(ApiError.class, () -> new PetAccess("http://127.0.0.1:9", "t").require("owner", "../x"));
    }
}
