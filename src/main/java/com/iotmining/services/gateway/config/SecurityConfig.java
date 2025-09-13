package com.iotmining.services.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
//                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/auth/**", "/public/**", "/actuator/**", "/fallback").permitAll()
                        .anyExchange().authenticated()
                )
//            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
                .oauth2ResourceServer(ServerHttpSecurity.OAuth2ResourceServerSpec::jwt);  // No custom config needed
        return http.build();
    }
}
