package com.ipets;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthUnitTest {
    private static String pkcs8() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    }

    @Test
    void issuedAccessTokenVerifiesWithIssuerAndAudience() throws Exception {
        var keys = new Keys();
        var key = Keys.parse(pkcs8());
        var tokens = new Tokens(null, keys.jwtEncoder(key), "https://ipets.test", "ipets-api", Duration.ofMinutes(15), Duration.ofDays(30));
        var jwt = keys.jwtDecoder(key, "https://ipets.test", "ipets-api").decode(tokens.access("user-1", "a@b.co"));
        assertEquals("user-1", jwt.getSubject());
        assertEquals("a@b.co", jwt.getClaimAsString("email"));
        assertThrows(JwtValidationException.class, () -> keys.jwtDecoder(key, "https://other.test", "ipets-api").decode(tokens.access("user-1", "a@b.co")));
    }

    @Test
    void tokenFromAnotherKeyIsRejected() throws Exception {
        var keys = new Keys();
        var mine = Keys.parse(pkcs8());
        var other = new Tokens(null, keys.jwtEncoder(Keys.parse(pkcs8())), "https://ipets.test", "ipets-api", Duration.ofMinutes(15), Duration.ofDays(30));
        assertThrows(BadJwtException.class, () -> keys.jwtDecoder(mine, "https://ipets.test", "ipets-api").decode(other.access("u", "e@x.co")));
    }

    @Test
    void audienceValidatorRejectsForeignAudience() {
        var jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u").audience(List.of("other")).expiresAt(Instant.now().plusSeconds(60)).build();
        assertTrue(Keys.audience("ipets-api").validate(jwt).hasErrors());
    }

    @Test
    void missingSigningKeyFailsStartup() {
        assertThrows(IllegalStateException.class, () -> Keys.parse(""));
        assertThrows(IllegalStateException.class, () -> Keys.parse("bm90LWEta2V5"));
    }

    @Test
    void refreshTokensAreRandomAndStoredOnlyAsHash() {
        var a = Tokens.randomToken();
        assertNotEquals(a, Tokens.randomToken());
        assertEquals(64, Tokens.hash(a).length());
        assertFalse(Tokens.hash(a).contains(a));
    }

    @Test
    void inputPolicy() {
        assertEquals("a@b.co", Policy.email("  A@B.co "));
        assertThrows(ApiError.class, () -> Policy.email("nope"));
        assertThrows(ApiError.class, () -> Policy.password("short1"));
        assertThrows(ApiError.class, () -> Policy.password("lettersonly"));
        assertEquals("PetCare123!", Policy.password("PetCare123!"));
        assertEquals("Alex Tan", Policy.name("  Alex   Tan "));
    }
}
