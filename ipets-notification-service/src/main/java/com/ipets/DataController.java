package com.ipets;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class DataController {
    private final Store store;
    private final Collections collections;

    DataController(Store store, Collections collections) {
        this.store = store; this.collections = collections;
    }

    @GetMapping("/api/v1/me")
    Map<String, String> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("ownerId", jwt.getSubject());
    }

    @GetMapping("/api/v1/sync")
    Map<String, Object> sync(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String since) {
        return store.sync(jwt.getSubject(), since);
    }

    @PutMapping("/api/v1/{collection}/{id}")
    Map<String, Object> put(@AuthenticationPrincipal Jwt jwt, @PathVariable String collection, @PathVariable String id, @RequestBody Map<String, Object> body) {
        var c = collections.get(collection);
        if (c.singleton() && !"me".equals(id)) throw Store.notFound();
        return store.upsert(jwt.getSubject(), c, id, body);
    }

    @DeleteMapping("/api/v1/{collection}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String collection, @PathVariable String id) {
        store.delete(jwt.getSubject(), collections.get(collection), id);
    }

    /** Account deletion: permanently removes every row this owner has in this service. */
    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAll(@AuthenticationPrincipal Jwt jwt) {
        store.deleteAll(jwt.getSubject());
    }
}
