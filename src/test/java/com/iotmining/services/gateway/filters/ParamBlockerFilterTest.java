package com.iotmining.services.gateway.filters;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config previously declared paramName/blockValue as private fields with
 * no getters/setters - Spring's reflection-based DataBinder needs real
 * accessors to bind route filter args onto it, so wiring this into any
 * route's YAML would have failed at startup. These tests exercise the
 * filter directly (bypassing YAML binding) to confirm the actual blocking
 * logic is correct now that Config can be populated at all.
 */
class ParamBlockerFilterTest {

    private final ParamBlockerFilter filter = new ParamBlockerFilter();

    @Test
    void blocksTheRequest_whenTheParamMatchesTheBlockValue() {
        var config = new ParamBlockerFilter.Config();
        config.setParamName("debug");
        config.setBlockValue("true");

        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/anything?debug=true").build());

        StepVerifier.create(filter.apply(config).filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void letsTheRequestThrough_whenTheParamDoesNotMatch() {
        var config = new ParamBlockerFilter.Config();
        config.setParamName("debug");
        config.setBlockValue("true");

        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/anything?debug=false").build());

        boolean[] chainInvoked = {false};
        StepVerifier.create(filter.apply(config).filter(exchange, ex -> {
                    chainInvoked[0] = true;
                    return Mono.empty();
                }))
                .verifyComplete();

        assertThat(chainInvoked[0]).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
