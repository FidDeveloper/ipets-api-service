package com.ipets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class DocumentTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private static byte[] bytes(int... values) {
        byte[] out = new byte[16];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    @Test void detectsAllowedTypesFromContent() {
        assertEquals("image/jpeg", FileStore.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0), 16));
        assertEquals("image/png", FileStore.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), 16));
        assertEquals("application/pdf", FileStore.sniff(bytes('%', 'P', 'D', 'F', '-', '1'), 16));
        assertEquals("image/heic", FileStore.sniff(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'), 16));
    }

    @Test void rejectsUnknownOrActiveContent() {
        assertNull(FileStore.sniff("<html><script>".getBytes(), 14));
        assertNull(FileStore.sniff(bytes('P', 'K', 3, 4), 16));
        assertNull(FileStore.sniff(bytes(0xFF, 0xD8), 2));
    }

    @Test void draftCollectionStoresJsonRows() {
        var body = new HashMap<String, Object>(Map.of("documentIds", List.of("d1"), "created", "2026-11-22", "engine", "fast", "warnings", List.of(), "rows", List.of(Map.of("rowId", "r1"))));
        var values = Spec.validate(Collections.DRAFTS, body, json);
        assertEquals("[\"d1\"]", values.get("document_ids"));
        body.put("rows", "not-a-list");
        assertThrows(ApiError.class, () -> Spec.validate(Collections.DRAFTS, body, json));
    }

    @Test void rejectsEveryTokenWithoutKeyConfiguration() {
        assertThrows(BadJwtException.class, () -> new SecurityConfig().decoder("https://ipets.test", "", "ipets-api").decode("forged"));
    }
}
