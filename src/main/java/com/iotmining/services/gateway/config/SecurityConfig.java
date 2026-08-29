package com.iotmining.services.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Preflight requests never carry an Authorization header (a
                        // browser strips it before sending OPTIONS) - without this,
                        // anyExchange().authenticated() below rejects every preflight
                        // with 401 before spring-cloud-gateway's own CorsWebFilter
                        // (configured via spring.cloud.gateway.globalcors in
                        // application-dev.yml/application-prod.yml) ever gets a
                        // chance to add the Access-Control-Allow-Origin header - the
                        // browser then reports a bare CORS failure with no useful
                        // detail, masking the real 401. Confirmed live: a real
                        // preflight OPTIONS from the dashboard's own dev origin
                        // (localhost:3000, already on the globalcors allowlist) came
                        // back 401 with no CORS headers at all before this fix. Same
                        // fix already applied to device-control-service and
                        // dashboard-management-service's own SecurityConfig this
                        // session - see their doc comments.
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/fallback").permitAll()
                        // Narrowed from "/actuator/**" - that previously exposed every
                        // actuator endpoint unauthenticated, including /actuator/env,
                        // /actuator/beans and (if ever enabled) /actuator/shutdown, on
                        // top of management.endpoints.web.exposure.include already
                        // being "*" (fixed separately in application.yml). Only the two
                        // endpoints an external load balancer/orchestrator actually
                        // needs to probe without a token stay open.
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        // The SockJS/STOMP handshake (telemetry-stream-service's
                        // WebSocketConfig registers it at this exact full path, no
                        // prefix-stripping - see tss-websocket-route in
                        // application-dev.yml/application-prod.yml) authenticates via
                        // a "?access_token=" query param, not an Authorization header
                        // - browsers can't set custom headers on a WebSocket upgrade,
                        // and SockJS's own pre-upgrade "/info" negotiation call
                        // doesn't send one either. anyExchange().authenticated() below
                        // was rejecting both with a bare 401 (no CORS headers, since
                        // Security short-circuits before CorsWebFilter runs - same
                        // failure shape as the OPTIONS case above, confirmed live).
                        // Safe to permit here: telemetry-stream-service's own
                        // SecurityConfig already permitAll's this exact path for the
                        // identical reason, relying on its StompAuthChannelInterceptor
                        // to validate the token at the STOMP CONNECT frame instead -
                        // same defense-in-depth split as every other route.
                        .pathMatchers("/api/v1/telemetry-stream/ws-telemetry/**").permitAll()
                        // Declared before the broader /api/v1/auth/** permit below -
                        // path-matcher rules are evaluated in order and the first match
                        // wins, so the more specific role check has to come first or the
                        // broad permit would swallow it. Defense-in-depth on top of
                        // auth-service's own check: this is the one path where getting
                        // authorization wrong at the gateway has the highest blast
                        // radius, so it gets a role check here in addition to (not
                        // instead of) whatever auth-service itself enforces once the
                        // request reaches it.
                        .pathMatchers("/api/v1/auth/super-admin/**").hasAuthority("ROLE_SUPER_ADMIN")
                        // The rest of auth-service's own path (login, register, token
                        // refresh, etc.) has to stay reachable without a pre-existing
                        // token - requiring one to reach the login endpoint would be
                        // circular. auth-service's own SecurityConfig is what actually
                        // protects its non-public sub-paths (e.g. logout, profile); the
                        // gateway's job here is routing, not redundant authentication -
                        // same defense-in-depth split as every other route (downstream
                        // independently re-validates the JWT itself rather than trusting
                        // a header this gateway would inject).
                        .pathMatchers("/api/v1/auth/**").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(SecurityConfig::convertRoleClaim)));
        return http.build();
    }

    /**
     * Spring Security's default JWT authorities converter only reads a
     * scope/scp claim - this platform's JWTs (issued by auth-service's
     * JwtTokenProvider) carry roles in a "role" claim instead
     * (claims.put("role", roles), each entry already a full authority
     * string like "ROLE_SUPER_ADMIN" - see LoginServiceInitializer).
     * Without this, the hasAuthority("ROLE_SUPER_ADMIN") check above could
     * never actually match anything - no authority would ever be
     * populated from a real token, silently blocking every caller
     * regardless of their real role.
     *
     * Deliberately NOT a separate @Bean of type Converter<Jwt, ...> -
     * WebFluxAutoConfiguration's own webFluxConversionService scans every
     * Converter-typed bean in the context to build its generic type-
     * conversion service, and chokes on a lambda's erased generics
     * ("Unable to determine source type <S> and target type <T>").
     * Building it inline here, referenced directly by the security filter
     * chain instead of exposed as a general-purpose bean, avoids that
     * entirely.
     */
    // Package-private (not private) so SecurityConfigTest can exercise the
    // claim-to-authority mapping directly, without standing up a full
    // reactive security filter chain just to test this one piece of logic.
    static Mono<AbstractAuthenticationToken> convertRoleClaim(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("role");
        Collection<GrantedAuthority> authorities = roles == null
                ? List.of()
                : roles.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        return Mono.just(new JwtAuthenticationToken(jwt, authorities));
    }
}
