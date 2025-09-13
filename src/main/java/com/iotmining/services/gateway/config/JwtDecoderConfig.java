package com.iotmining.services.gateway.config;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.SecretKey;


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


//    @Bean
//    public ReactiveJwtDecoder jwtDecoder() {
//        byte[] decodedKey = Decoders.BASE64.decode(secret);
//        SecretKey originalKey = Keys.hmacShaKeyFor(decodedKey);
//        System.out.println("originalKey: "+originalKey);
//        return NimbusReactiveJwtDecoder.withSecretKey(originalKey).build();
//    }
}
