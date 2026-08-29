package com.iotmining.services.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Proves the gateway's actual route table forwards real HTTP requests to
 * the intended backend - not a config-shape assertion, an actual proxied
 * request/response round trip through the real Spring context.
 *
 * One shared WireMock instance stands in for every downstream service
 * (all 14 distinct Eureka names in the route table resolve to its port via
 * Spring Cloud LoadBalancer's "simple" discovery client - see
 * application-test.yml). That's enough to prove routing correctness: each
 * route's path predicate has to independently steer a request to the
 * right stub, since WireMock itself only ever sees one port regardless of
 * which lb:// name resolved to it - proving *that* resolution mechanism
 * works is Spring Cloud LoadBalancer's own well-tested job, not this
 * platform's route table's.
 *
 * Activates "dev,test" - "dev" for the real route table under test,
 * "test" (application-test.yml) only to disable the real Eureka client so
 * the simple one registered here is the only DiscoveryClient in play.
 * Needs a real local Redis (RequestRateLimiter is wired into most routes) -
 * same assumption every other manual/automated check on this platform
 * already makes rather than adding Testcontainers for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "test"})
class RouteEndpointsIT {

    // Every distinct lb:// service name used anywhere in application-dev.yml's
    // route table - kept as a single list so a new route referencing a new
    // service name is a one-line addition here, not a hunt through the YAML.
    private static final List<String> BACKEND_SERVICE_NAMES = List.of(
            "IoTMining-Auth-Service",
            "dashboard-management-service",
            "IoTMining-Data-Factory-Service",
            "device-control-service",
            "device-management-service",
            "IoTMining-Device-Profile-Service",
            "ai-service",
            "IoTMining-Notification-Service",
            "Object-Storage-Service",
            "ocpp-service",
            "rulechain-service",
            "telemetry-stream-service",
            "tenant-management-service",
            "user-settings-service"
    );

    // Matches application-dev.yml's own dev-secret fallback exactly - the
    // property that JWT_SECRET (see application-dev.yml's own comment)
    // would otherwise override isn't set in a test run, so this fallback
    // is what's actually active, same as a real local dev boot.
    private static final String JWT_SECRET = "Vlo2vcFdiXGgWqZLEpLw6kk99sH8/4odgC2XgZV0IbA=";

    private static WireMockServer wireMock;

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void pointEveryBackendAtWireMock(DynamicPropertyRegistry registry) {
        // @DynamicPropertySource methods run before @BeforeAll, so WireMock
        // has to be started here too (its port isn't known otherwise) -
        // stopped in @BeforeAll's own started instance is fine, it's the
        // same static field either way.
        if (wireMock == null) {
            wireMock = new WireMockServer(wireMockConfig().dynamicPort());
            wireMock.start();
        }
        String uri = "http://localhost:" + wireMock.port();
        for (String name : BACKEND_SERVICE_NAMES) {
            registry.add("spring.cloud.discovery.client.simple.instances." + name + "[0].uri", () -> uri);
        }
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + gatewayPort)
                    .responseTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return client;
    }

    @AfterEach
    void resetStubs() {
        if (wireMock != null) {
            wireMock.resetAll();
        }
    }

    private String tokenWithRoles(String... roles) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("route-endpoints-it")
                .claim("role", List.of(roles))
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    /**
     * (route id, path to call, backend path WireMock should actually
     * receive - differs from the call path only where StripPrefix applies).
     */
    private record RouteCase(String routeId, String callPath, String backendPath) {
        RouteCase(String routeId, String path) {
            this(routeId, path, path);
        }
    }

    static Stream<Arguments> authenticatedRoutes() {
        return Stream.of(
                new RouteCase("dashboard-route", "/api/dashboards/list"),
                new RouteCase("datafactory-route", "/thresholds/abc"),
                new RouteCase("device-control-commands-route", "/api/v1/devices/dev-1/commands"),
                new RouteCase("device-control-status-route", "/api/v1/commands/cmd-1"),
                new RouteCase("dms-route", "/api/v1/devices"),
                new RouteCase("device-profile-route", "/api/v1/device-profile/abc"),
                new RouteCase("ai-v1-route", "/api/v1/ai/abc"),
                new RouteCase("ai-legacy-route", "/api/ai/abc"),
                new RouteCase("notification-route", "/api/notifications/abc"),
                new RouteCase("object-storage-route", "/api/v1/object/abc"),
                new RouteCase("ocpp-route", "/api/chargepoints/abc"),
                new RouteCase("rule-engine-route", "/api/v1/rule-engine/abc"),
                // StripPrefix=3 removes /api/v1/telemetry-stream before forwarding -
                // the real controller's path is bare /history, no prefix at all.
                new RouteCase("tss-rest-route", "/api/v1/telemetry-stream/history", "/history"),
                new RouteCase("tms-route", "/api/v1/tenants/abc"),
                new RouteCase("user-settings-route", "/api/v1/settings/users/abc")
        ).map(rc -> Arguments.of(rc.routeId(), rc.callPath(), rc.backendPath()));
    }

    @ParameterizedTest(name = "{0}: {1} reaches its intended backend, authenticated")
    @MethodSource("authenticatedRoutes")
    void authenticatedRouteReachesIntendedBackend(String routeId, String callPath, String backendPath) {
        wireMock.stubFor(get(urlEqualTo(backendPath))
                .willReturn(okJson("{\"answeredBy\":\"" + routeId + "\"}")));

        client().get().uri(callPath)
                .header("Authorization", "Bearer " + tokenWithRoles("ROLE_USER"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.answeredBy").isEqualTo(routeId);

        wireMock.verify(getRequestedFor(urlEqualTo(backendPath)));
    }

    @ParameterizedTest(name = "{0}: {1} rejected with 401 when unauthenticated, never reaches the backend")
    @MethodSource("authenticatedRoutes")
    void authenticatedRouteRejectsUnauthenticatedCallerBeforeReachingBackend(
            String routeId, String callPath, String backendPath) {
        wireMock.stubFor(get(urlEqualTo(backendPath))
                .willReturn(okJson("{\"answeredBy\":\"" + routeId + "\"}")));

        client().get().uri(callPath)
                .exchange()
                .expectStatus().isUnauthorized();

        wireMock.verify(0, getRequestedFor(urlEqualTo(backendPath)));
    }

    @Test
    void authRoute_isReachableWithoutAnyToken() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/auth/login"))
                .willReturn(okJson("{\"answeredBy\":\"auth-route\"}")));

        client().get().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.answeredBy").isEqualTo("auth-route");
    }

    @Test
    void tssWebsocketRoute_reachableWithoutAToken_andPathIsNotStripped() {
        // Deliberately the full, unstripped path - matches how
        // telemetry-stream-service's own WebSocketConfig registers this
        // endpoint (fixed earlier this session), and proves this route's
        // predicate really does skip StripPrefix, unlike tss-rest-route.
        String path = "/api/v1/telemetry-stream/ws-telemetry/info";
        wireMock.stubFor(get(urlEqualTo(path))
                .willReturn(okJson("{\"answeredBy\":\"tss-websocket-route\"}")));

        client().get().uri(path)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.answeredBy").isEqualTo("tss-websocket-route");
    }

    @Test
    void superAdminPath_reachableWithSuperAdminRole() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/auth/super-admin/users"))
                .willReturn(okJson("{\"answeredBy\":\"auth-route\"}")));

        client().get().uri("/api/v1/auth/super-admin/users")
                .header("Authorization", "Bearer " + tokenWithRoles("ROLE_SUPER_ADMIN"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void superAdminPath_rejectedForAPlainUser_neverReachesTheBackend() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/auth/super-admin/users"))
                .willReturn(okJson("{\"answeredBy\":\"auth-route\"}")));

        client().get().uri("/api/v1/auth/super-admin/users")
                .header("Authorization", "Bearer " + tokenWithRoles("ROLE_USER"))
                .exchange()
                .expectStatus().isForbidden();

        wireMock.verify(0, getRequestedFor(urlEqualTo("/api/v1/auth/super-admin/users")));
    }

    @Test
    void deviceControlAndDeviceManagementCollision_bothResolveToTheirOwnIntendedRoute() {
        // The same test data already covers this via the parameterized
        // matrix above (device-control-commands-route vs. dms-route both
        // under /api/v1/devices/**), but this makes the specific ordering
        // claim explicit and self-contained: a commands sub-path must not
        // fall through to device-management-service's broader route.
        wireMock.stubFor(get(urlEqualTo("/api/v1/devices/dev-42/commands"))
                .willReturn(okJson("{\"answeredBy\":\"device-control-commands-route\"}")));
        wireMock.stubFor(get(urlEqualTo("/api/v1/devices/dev-42"))
                .willReturn(okJson("{\"answeredBy\":\"dms-route\"}")));

        String token = "Bearer " + tokenWithRoles("ROLE_USER");

        client().get().uri("/api/v1/devices/dev-42/commands")
                .header("Authorization", token)
                .exchange()
                .expectBody().jsonPath("$.answeredBy").isEqualTo("device-control-commands-route");

        client().get().uri("/api/v1/devices/dev-42")
                .header("Authorization", token)
                .exchange()
                .expectBody().jsonPath("$.answeredBy").isEqualTo("dms-route");
    }

    @Test
    void unknownPath_matchesNoRoute_getsA404NotA500() {
        client().get().uri("/api/v1/this-does-not-exist")
                .header("Authorization", "Bearer " + tokenWithRoles("ROLE_USER"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void corsPreflight_getsCorrectHeaders_withoutReachingAnyBackend() {
        client().options().uri("/api/dashboards/list")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }
}
