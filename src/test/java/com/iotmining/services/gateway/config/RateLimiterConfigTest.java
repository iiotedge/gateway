package com.iotmining.services.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * RequestRateLimiter had no KeyResolver bean at all before this pass - the
 * platform-default PrincipalNameKeyResolver collapses every unauthenticated
 * caller into one shared bucket, defeating the point of per-caller rate
 * limiting on the public routes that don't require a token. This pins down
 * both branches: authenticated -> keyed by subject, unauthenticated ->
 * keyed by remote address.
 */
class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    void keyResolver_usesAuthenticationName_whenAuthenticated() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/anything").build());

        var authentication = new TestingAuthenticationToken("user-42", null);
        authentication.setAuthenticated(true);
        var securityContext = new SecurityContextImpl(authentication);

        Mono<String> result = config.keyResolver().resolve(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));

        StepVerifier.create(result)
                .expectNext("user-42")
                .verifyComplete();
    }

    @Test
    void keyResolver_fallsBackToRemoteAddress_whenUnauthenticated() {
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getLoopbackAddress(), 54321);
        var request = MockServerHttpRequest.get("/anything").remoteAddress(remote).build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(config.keyResolver().resolve(exchange))
                .expectNext(remote.getAddress().getHostAddress())
                .verifyComplete();
    }

    @Test
    void keyResolver_returnsUnknown_whenRemoteAddressIsUnavailable() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/anything").build());

        StepVerifier.create(config.keyResolver().resolve(exchange))
                .expectNext("unknown")
                .verifyComplete();
    }
}
