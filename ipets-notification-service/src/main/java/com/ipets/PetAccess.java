package com.ipets;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks pet-service (the owner of pet access) whether a pet belongs to the caller; caches only positive answers briefly. */
@Component
class PetAccess {
    private static final long TTL_SECONDS = 60;
    private final RestClient http;
    private final String token;
    private final Map<String, Instant> allowed = new ConcurrentHashMap<>();

    PetAccess(@Value("${ipets.pet-service-url}") String baseUrl, @Value("${ipets.internal-token}") String token) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.token = token;
    }

    void require(String owner, String petId) {
        if (petId == null || !petId.matches("[A-Za-z0-9-]{1,36}")) throw notOwned();
        String key = owner + "/" + petId;
        Instant until = allowed.get(key);
        if (until != null && until.isAfter(Instant.now())) return;
        Map<?, ?> answer;
        try {
            answer = http.get().uri("/internal/pets/{pet}/owners/{owner}", petId, owner).header("X-Internal-Token", token).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "pet_service_unavailable", "Cannot verify the pet right now; try again.");
        }
        if (answer == null || !Boolean.TRUE.equals(answer.get("owned"))) throw notOwned();
        allowed.put(key, Instant.now().plusSeconds(TTL_SECONDS));
    }

    private static ApiError notOwned() {
        return new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "pet_not_found", "Choose one of your pets.");
    }
}
