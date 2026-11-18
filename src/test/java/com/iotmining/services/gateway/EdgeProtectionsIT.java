package com.iotmining.services.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Runtime checks that the edge protections configured in application.yml and
 * application-dev.yml are still applied to proxied traffic: the Redis-backed
 * RequestRateLimiter (headers and per-caller buckets), the security response headers from
 * default-filters, and server.forward-headers-strategy=framework, which registers
 * ForwardedHeaderTransformer so the remote address used by RateLimiterConfig comes from
 * X-Forwarded-For. Which key RateLimiterConfig then derives for a forwarded request is
 * not asserted here, only that it differs from a direct caller's.
 *
 * Needs a running Redis (REDIS_HOST/REDIS_PORT, localhost:6379 by default; the CI verify
 * job starts one). If Redis is not reachable, RedisRateLimiter lets requests through with
 * X-RateLimit-Remaining=-1, which these checks report as a failure instead of passing.
 *
 * Signs tokens with a key generated for this run rather than the profile's configured one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "test"})
class EdgeProtectionsIT {

    private static final Logger log = LoggerFactory.getLogger(EdgeProtectionsIT.class);

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    // auth-route's RequestRateLimiter arguments in application-dev.yml.
    private static final int REPLENISH_RATE = 10;
    private static final int BURST_CAPACITY = 20;

    // Upper bound for draining one caller's bucket: burst capacity plus whatever is
    // replenished while the requests are being sent.
    private static final int MAX_REQUESTS_TO_EXHAUST = 200;

    private static final SecretKey SIGNING_KEY = Jwts.SIG.HS256.key().build();

    private static WireMockServer authBackend;

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Runs before @BeforeAll, so the backend is started here to know its port.
        if (authBackend == null) {
            authBackend = new WireMockServer(wireMockConfig().dynamicPort());
            authBackend.start();
        }
        registry.add("spring.security.oauth2.resourceserver.jwt.secret-key",
                () -> Encoders.BASE64.encode(SIGNING_KEY.getEncoded()));
        registry.add("spring.cloud.discovery.client.simple.instances.IoTMining-Auth-Service[0].uri",
                () -> "http://localhost:" + authBackend.port());
    }

    @AfterAll
    static void stopBackend() {
        if (authBackend != null) {
            authBackend.stop();
        }
    }

    @BeforeEach
    void stubLogin() {
        authBackend.resetAll();
        authBackend.stubFor(post(urlEqualTo(LOGIN_PATH))
                .willReturn(okJson("{\"answeredBy\":\"auth-route\"}")));
    }

    @Test
    void loginResponse_carriesTheRateLimitHeaders() {
        EntityExchangeResult<byte[]> response = login(null, null);
        HttpHeaders headers = response.getResponseHeaders();
        log.info("POST {} -> {}; X-RateLimit-Remaining={} Replenish-Rate={} Burst-Capacity={} Requested-Tokens={}",
                LOGIN_PATH, response.getStatus().value(),
                headers.get("X-RateLimit-Remaining"), headers.get("X-RateLimit-Replenish-Rate"),
                headers.get("X-RateLimit-Burst-Capacity"), headers.get("X-RateLimit-Requested-Tokens"));

        assertThat(response.getStatus().value()).isEqualTo(200);
        assertThat(headers.getFirst("X-RateLimit-Replenish-Rate")).isEqualTo(String.valueOf(REPLENISH_RATE));
        assertThat(headers.getFirst("X-RateLimit-Burst-Capacity")).isEqualTo(String.valueOf(BURST_CAPACITY));
        assertThat(headers.getFirst("X-RateLimit-Requested-Tokens")).isEqualTo("1");
        assertThat(remaining(response)).isBetween(0, BURST_CAPACITY - 1);
    }

    @Test
    void proxiedResponse_carriesTheConfiguredSecurityHeaders() {
        EntityExchangeResult<byte[]> response = login(null, null);
        HttpHeaders headers = response.getResponseHeaders();
        log.info("POST {} -> {}; X-Frame-Options={} X-Content-Type-Options={} Strict-Transport-Security={} X-XSS-Protection={}",
                LOGIN_PATH, response.getStatus().value(), headers.get("X-Frame-Options"),
                headers.get("X-Content-Type-Options"), headers.get("Strict-Transport-Security"),
                headers.get("X-XSS-Protection"));

        assertThat(response.getStatus().value()).isEqualTo(200);
        assertThat(new String(response.getResponseBody(), StandardCharsets.UTF_8)).contains("auth-route");
        authBackend.verify(postRequestedFor(urlEqualTo(LOGIN_PATH)));

        assertThat(headers.get("X-Frame-Options")).contains("DENY");
        assertThat(headers.get("X-Content-Type-Options")).contains("nosniff");
        // Spring Security writes X-Frame-Options and X-Content-Type-Options by default as
        // well, but HSTS only for HTTPS requests, so over plain HTTP this value can only
        // come from the gateway's AddResponseHeader default filter.
        assertThat(headers.get("Strict-Transport-Security")).contains("max-age=31536000; includeSubDomains");
    }

    @Test
    void authenticatedCallers_eachGetTheirOwnBucket() {
        String first = userToken("edge-it-" + UUID.randomUUID());
        String second = userToken("edge-it-" + UUID.randomUUID());

        int sent = exhaust(() -> login(null, first));
        EntityExchangeResult<byte[]> secondCaller = login(null, second);
        log.info("first subject limited after {} requests; second subject -> {} remaining={}",
                sent, secondCaller.getStatus().value(), secondCaller.getResponseHeaders().get("X-RateLimit-Remaining"));

        assertThat(secondCaller.getStatus().value()).isEqualTo(200);
        assertThat(remaining(secondCaller)).isEqualTo(BURST_CAPACITY - 1);
    }

    @Test
    void forwardedHeaderTransformer_isRegistered() {
        assertThat(context.containsBean(WebHttpHandlerBuilder.FORWARDED_HEADER_TRANSFORMER_BEAN_NAME)).isTrue();
        assertThat(context.getBean(WebHttpHandlerBuilder.FORWARDED_HEADER_TRANSFORMER_BEAN_NAME))
                .isInstanceOf(ForwardedHeaderTransformer.class);
    }

    /**
     * Without ForwardedHeaderTransformer both callers below would have the same remote
     * address (the test client's own) and share one bucket. Tokens are replenished in whole
     * seconds, so the check is only accepted when the forwarded caller is still limited
     * right after the direct request; otherwise it is repeated.
     */
    @Test
    void forwardedCaller_isRateLimitedApartFromADirectCaller() {
        String forwardedFor = testNetAddress("198.51.100.");
        for (int attempt = 1; attempt <= 5; attempt++) {
            int sent = exhaust(() -> login(forwardedFor, null));
            EntityExchangeResult<byte[]> direct = login(null, null);
            EntityExchangeResult<byte[]> forwardedAgain = login(forwardedFor, null);
            log.info("attempt {}: forwarded caller limited after {} requests; direct -> {} remaining={}; forwarded again -> {}",
                    attempt, sent, direct.getStatus().value(), direct.getResponseHeaders().get("X-RateLimit-Remaining"),
                    forwardedAgain.getStatus().value());

            if (forwardedAgain.getStatus().value() != 429) {
                continue;
            }
            assertThat(direct.getStatus().value())
                    .as("direct caller right after the forwarded caller was limited")
                    .isEqualTo(200);
            return;
        }
        fail("tokens were replenished between every attempt; could not compare the two callers");
    }

    /** Sends requests as one caller until the limiter answers 429; returns how many it took. */
    private int exhaust(Supplier<EntityExchangeResult<byte[]>> request) {
        for (int sent = 1; sent <= MAX_REQUESTS_TO_EXHAUST; sent++) {
            EntityExchangeResult<byte[]> response = request.get();
            int status = response.getStatus().value();
            if (status == 429) {
                return sent;
            }
            assertThat(status).as("status while draining a bucket").isEqualTo(200);
            remaining(response);
        }
        return fail("no 429 after %d requests from one caller", MAX_REQUESTS_TO_EXHAUST);
    }

    private static int remaining(EntityExchangeResult<?> response) {
        String value = response.getResponseHeaders().getFirst("X-RateLimit-Remaining");
        assertThat(value).as("X-RateLimit-Remaining").isNotNull();
        int remaining = Integer.parseInt(value);
        assertThat(remaining)
                .as("X-RateLimit-Remaining (-1 means the rate limiter could not reach Redis)")
                .isNotNegative();
        return remaining;
    }

    private EntityExchangeResult<byte[]> login(String forwardedFor, String token) {
        WebTestClient.RequestBodySpec request = client().post().uri(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.bodyValue("{}").exchange().expectBody().returnResult();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static String testNetAddress(String prefix) {
        return prefix + ThreadLocalRandom.current().nextInt(1, 255);
    }

    private static String userToken(String subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("role", List.of("ROLE_USER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(SIGNING_KEY)
                .compact();
    }
}
