package com.iotmining.services.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime checks for the actuator "gateway" endpoint. application.yml exposes it with
 * management.endpoint.gateway.access=read-only, and SecurityConfig lets any authenticated
 * caller reach /actuator/gateway/**. With read-only access only the endpoint's GET/HEAD
 * operations may be mapped: a regular user token can read the route table, but saving,
 * deleting or refreshing routes must be rejected before any write handler runs.
 *
 * Every write attempt sends something the handler would accept (a valid route definition,
 * an id that exists in the writable route repository), so a 2xx answer or a changed route
 * repository means the operation was reachable, not that the input was wrong. Write
 * attempts use a ROLE_USER token; the follow-up reads that check nothing changed use a
 * ROLE_SUPER_ADMIN token, so they keep working if reading is later limited to that role.
 *
 * Signs tokens with a key generated for this run rather than the profile's configured one.
 * No downstream service is called, so neither WireMock nor Redis is needed here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "test"})
class ActuatorGatewayEndpointIT {

    private static final Logger log = LoggerFactory.getLogger(ActuatorGatewayEndpointIT.class);

    /** Statuses a request may get when no write handler is mapped for it. */
    private static final Set<Integer> REJECTED = Set.of(403, 404, 405);

    private static final SecretKey SIGNING_KEY = Jwts.SIG.HS256.key().build();

    private static final String PROBE_ROUTE_ID = "probe-route";

    private static final String PROBE_ROUTE_JSON = """
            {"id":"probe-route","uri":"http://127.0.0.1:9","order":0,
             "predicates":[{"name":"Path","args":{"_genkey_0":"/probe-route/**"}}],
             "filters":[]}
            """;

    private static final String BULK_PROBE_ROUTE_JSON = """
            [{"id":"probe-route-bulk","uri":"http://127.0.0.1:9","order":0,
              "predicates":[{"name":"Path","args":{"_genkey_0":"/probe-route-bulk/**"}}],
              "filters":[]}]
            """;

    @DynamicPropertySource
    static void useGeneratedSigningKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.secret-key",
                () -> Encoders.BASE64.encode(SIGNING_KEY.getEncoded()));
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private RouteDefinitionWriter routeDefinitionWriter;

    @Test
    void routeTable_isReadableWithAUserToken_andMatchesTheLiveRoutes() {
        List<Map<String, Object>> routes = routesFromEndpoint("ROLE_USER");
        List<String> liveIds = liveRouteIds();

        log.info("GET /actuator/gateway/routes with a ROLE_USER token -> 200, {} routes; first entry keys {}",
                routes.size(), routes.isEmpty() ? List.of() : routes.get(0).keySet());

        assertThat(routes).extracting(r -> (String) r.get("route_id"))
                .contains("auth-route")
                .containsExactlyInAnyOrderElementsOf(liveIds);
    }

    @Test
    void routeTable_requiresAuthentication() {
        client().get().uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refreshingRoutes_isRejected() {
        assertRejected(HttpMethod.POST, "/actuator/gateway/refresh", null);
    }

    @Test
    void savingARoute_isRejected_andTheRouteIsNotStored() {
        assertRejected(HttpMethod.POST, "/actuator/gateway/routes/" + PROBE_ROUTE_ID, PROBE_ROUTE_JSON);

        assertThat(storedRouteIds()).doesNotContain(PROBE_ROUTE_ID);
        assertThat(routesFromEndpoint("ROLE_SUPER_ADMIN")).extracting(r -> r.get("route_id"))
                .doesNotContain(PROBE_ROUTE_ID);
    }

    @Test
    void savingRoutesInBulk_isRejected_andNothingIsStored() {
        assertRejected(HttpMethod.POST, "/actuator/gateway/routes", BULK_PROBE_ROUTE_JSON);

        assertThat(storedRouteIds()).doesNotContain("probe-route-bulk");
    }

    @Test
    void deletingAConfiguredRoute_isRejected() {
        assertRejected(HttpMethod.DELETE, "/actuator/gateway/routes/auth-route", null);

        assertThat(liveRouteIds()).contains("auth-route");
    }

    /**
     * Routes from application-dev.yml are not held in the writable repository, so a DELETE
     * for one of them could also answer 404 from the handler itself. This variant seeds a
     * route the handler would really delete and checks that it is still there afterwards.
     */
    @Test
    void deletingARouteHeldInTheWritableRepository_isRejected_andTheRouteIsKept() {
        String id = "delete-probe-route";
        RouteDefinition definition = new RouteDefinition();
        definition.setId(id);
        definition.setUri(URI.create("http://127.0.0.1:9"));
        definition.setPredicates(List.of(new PredicateDefinition("Path=/delete-probe-route/**")));
        routeDefinitionWriter.save(Mono.just(definition)).block(Duration.ofSeconds(10));
        try {
            assertThat(storedRouteIds()).contains(id);

            assertRejected(HttpMethod.DELETE, "/actuator/gateway/routes/" + id, null);

            assertThat(storedRouteIds()).contains(id);
        } finally {
            routeDefinitionWriter.delete(Mono.just(id))
                    .onErrorResume(e -> Mono.empty())
                    .block(Duration.ofSeconds(10));
        }
    }

    private void assertRejected(HttpMethod method, String path, String jsonBody) {
        WebTestClient.RequestBodySpec request = client().method(method).uri(path)
                .header("Authorization", "Bearer " + token("ROLE_USER"));
        WebTestClient.RequestHeadersSpec<?> ready = jsonBody == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).bodyValue(jsonBody);

        int status = ready.exchange().expectBody().returnResult().getStatus().value();
        log.info("{} {} with a ROLE_USER token -> {}", method, path, status);

        assertThat(status)
                .as("%s %s must not reach a write handler of the read-only gateway endpoint", method, path)
                .isIn(REJECTED);
    }

    private List<Map<String, Object>> routesFromEndpoint(String role) {
        return client().get().uri("/actuator/gateway/routes")
                .header("Authorization", "Bearer " + token(role))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                })
                .returnResult()
                .getResponseBody();
    }

    private List<String> liveRouteIds() {
        return routeLocator.getRoutes().map(Route::getId).collectList().block(Duration.ofSeconds(10));
    }

    private List<String> storedRouteIds() {
        return routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId)
                .collectList().block(Duration.ofSeconds(10));
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static String token(String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("actuator-endpoint-it")
                .claim("role", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(SIGNING_KEY)
                .compact();
    }
}
