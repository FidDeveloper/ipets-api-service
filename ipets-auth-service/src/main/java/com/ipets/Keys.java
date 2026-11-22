package com.ipets;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;

@Configuration
class Keys {
    static RSAKey parse(String base64Pkcs8) {
        if (base64Pkcs8 == null || base64Pkcs8.isBlank())
            throw new IllegalStateException("IPETS_JWT_PRIVATE_KEY is not configured; run setup_local.py");
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8.trim())));
            var publicKey = (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            var key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("IPETS_JWT_PRIVATE_KEY is not a valid RSA PKCS#8 key", e);
        }
    }

    static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected) ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
    }

    @Bean
    RSAKey signingKey(@Value("${ipets.jwt.private-key}") String key) {
        return parse(key);
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(List.of(key))));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey key, @Value("${ipets.jwt.issuer}") String issuer, @Value("${ipets.jwt.audience}") String audience) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience(audience)));
        return decoder;
    }
}
