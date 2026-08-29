package com.iotmining.services.gateway.config;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Builds the shared-secret HMAC decoder every service on this platform's
 * JWTs are validated against (same key convention as auth-service's
 * signer). This is the gateway's own edge-level check - downstream
 * services independently re-validate the same JWT themselves rather than
 * trusting a header this gateway would inject, so a JWT rejected here
 * never reaches them, but nothing downstream is relying solely on this
 * check having happened.
 */
@Configuration
public class JwtDecoderConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.secret-key}")
    private String secret;

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        return NimbusReactiveJwtDecoder.withSecretKey(
                Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
        ).build();
    }
}
