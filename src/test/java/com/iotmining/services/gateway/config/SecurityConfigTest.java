package com.iotmining.services.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the one piece of custom logic in SecurityConfig: mapping this
 * platform's "role" JWT claim onto Spring Security authorities. Without
 * this working correctly, the super-admin path's hasAuthority check can
 * never match anything real - exactly the class of bug this test guards
 * against (verified live against a real token during development; this
 * pins that behavior down as an automated regression test).
 */
class SecurityConfigTest {

    private Jwt jwtWithRoles(List<String> roles) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", "test-user")
                .claim("role", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    void convertRoleClaim_mapsEachRoleStringToAGrantedAuthority_unchanged() {
        Jwt jwt = jwtWithRoles(List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));

        var authorities = SecurityConfig.convertRoleClaim(jwt).block().getAuthorities();

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
    }

    @Test
    void convertRoleClaim_producesNoAuthorities_whenRoleClaimIsAbsent() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", "test-user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        var authorities = SecurityConfig.convertRoleClaim(jwt).block().getAuthorities();

        assertThat(authorities).isEmpty();
    }

    @Test
    void convertRoleClaim_authenticationCarriesTheOriginalJwtAsItsPrincipal() {
        Jwt jwt = jwtWithRoles(List.of("ROLE_USER"));

        var token = SecurityConfig.convertRoleClaim(jwt).block();

        assertThat(token.getPrincipal()).isEqualTo(jwt);
        assertThat(token.isAuthenticated()).isTrue();
    }
}
