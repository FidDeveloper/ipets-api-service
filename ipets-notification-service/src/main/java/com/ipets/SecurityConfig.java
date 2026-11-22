package com.ipets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected) ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
    }

    /** Service-to-service calls (never routed by the public edge). A blank configured token rejects everything. */
    static void requireInternal(String expected, String presented) {
        if (expected == null || expected.isBlank() || presented == null
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8)))
            throw new ApiError(HttpStatus.UNAUTHORIZED, "internal_auth", "Internal authentication failed.");
    }

    @Bean
    JwtDecoder decoder(@Value("${ipets.jwt.issuer}") String issuer, @Value("${ipets.jwt.jwks-uri:}") String jwks, @Value("${ipets.jwt.audience}") String audience) {
        // Reject all credentials until the real issuer is configured; never enable a development bypass.
        if (jwks.isBlank()) return token -> { throw new BadJwtException("JWT verification is not configured"); };
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience(audience)));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**", "/internal/**").permitAll().anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})).build();
    }
}
