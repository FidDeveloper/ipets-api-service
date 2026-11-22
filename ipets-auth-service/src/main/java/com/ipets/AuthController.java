package com.ipets;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class AuthController {
    record Register(String name, String email, String password) {}
    record Login(String email, String password) {}
    record Refresh(String refreshToken) {}
    record Rename(String name) {}
    record Session(String accessToken, String refreshToken, long expiresIn, Accounts.User user) {}

    private final Accounts accounts;
    private final Tokens tokens;
    private final Map<String, Object> jwks;

    AuthController(Accounts accounts, Tokens tokens, RSAKey key) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.jwks = new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    private Session session(Accounts.User user) {
        var pair = tokens.issue(user.id(), user.email(), null);
        return new Session(pair.accessToken(), pair.refreshToken(), pair.expiresIn(), user);
    }

    @PostMapping("/api/v1/register")
    @ResponseStatus(HttpStatus.CREATED)
    Session register(@RequestBody Register body) {
        return session(accounts.register(body.name(), body.email(), body.password()));
    }

    @PostMapping("/api/v1/login")
    Session login(@RequestBody Login body) {
        return session(accounts.login(body.email(), body.password()));
    }

    @PostMapping("/api/v1/refresh")
    Session refresh(@RequestBody Refresh body) {
        var pair = tokens.refresh(body.refreshToken());
        return new Session(pair.accessToken(), pair.refreshToken(), pair.expiresIn(), null);
    }

    @PostMapping("/api/v1/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody Refresh body) {
        tokens.logout(body.refreshToken());
    }

    @GetMapping("/api/v1/me")
    Accounts.User me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.get(jwt.getSubject());
    }

    @PatchMapping("/api/v1/me")
    Accounts.User rename(@AuthenticationPrincipal Jwt jwt, @RequestBody Rename body) {
        return accounts.rename(jwt.getSubject(), body.name());
    }

    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt) {
        accounts.delete(jwt.getSubject());
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return jwks;
    }

    /** Local/pilot only: creates the documented demo login when IPETS_DEMO_USER_ENABLED=true. */
    @Bean
    static ApplicationRunner demoUser(Accounts accounts, @Value("${ipets.demo-user.enabled}") boolean enabled) {
        return args -> { if (enabled) accounts.ensureDemo("demo@ipets.app", "Alex Tan", "PetCare123!"); };
    }
}
