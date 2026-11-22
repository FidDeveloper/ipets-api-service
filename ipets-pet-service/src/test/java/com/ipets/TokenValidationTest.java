package com.ipets;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class TokenValidationTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Spec.Collection pets = Collections.PETS;

    private Jwt token(String audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("owner-1").audience(List.of(audience)).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private Map<String, Object> pet() {
        return new java.util.HashMap<>(Map.of("name", "Luna", "species", "Cat", "breed", "BSH", "sex", "Female", "birthday", "2023-03-18", "weight", 4.2));
    }

    @Test void audience() {
        assertTrue(SecurityConfig.audience("ipets-api").validate(token("other")).hasErrors());
        assertFalse(SecurityConfig.audience("ipets-api").validate(token("ipets-api")).hasErrors());
    }

    @Test void rejectsEveryTokenWithoutKeyConfiguration() {
        assertThrows(BadJwtException.class, () -> new SecurityConfig().decoder("https://ipets.test", "", "ipets-api").decode("forged"));
    }

    @Test void internalTokenMustMatchAndBeConfigured() {
        assertThrows(ApiError.class, () -> SecurityConfig.requireInternal("", ""));
        assertThrows(ApiError.class, () -> SecurityConfig.requireInternal("secret", "wrong"));
        assertDoesNotThrow(() -> SecurityConfig.requireInternal("secret", "secret"));
    }

    @Test void validPetMapsToTypedColumns() {
        var values = Spec.validate(pets, pet(), json);
        assertEquals(java.sql.Date.valueOf("2023-03-18"), values.get("birthday"));
        assertEquals(false, values.get("sterilized"));
        assertEquals("", values.get("photo_file_id"));
    }

    @Test void rejectsInvalidFields() {
        for (var change : List.<Map<String, Object>>of(Map.of("name", " "), Map.of("species", "Dragon"), Map.of("birthday", "2023-02-30"),
                Map.of("weight", -1), Map.of("weight", "4"), Map.of("sterilized", "yes"))) {
            var body = pet(); body.putAll(change);
            assertThrows(ApiError.class, () -> Spec.validate(pets, body, json), change.toString());
        }
    }

    @Test void idsAreBounded() {
        assertThrows(ApiError.class, () -> Store.checkId("../etc"));
        assertThrows(ApiError.class, () -> Store.checkId("x".repeat(37)));
        assertDoesNotThrow(() -> Store.checkId("pet-luna"));
    }
}
