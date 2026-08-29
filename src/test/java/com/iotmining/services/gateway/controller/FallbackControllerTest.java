package com.iotmining.services.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    void returnsRealJsonBody_notABareString() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tenants/xyz"));

        var response = controller.fallback(exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");

        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isEqualTo("service_unavailable");
        assertThat(body.get("path")).isEqualTo("/api/v1/tenants/xyz");
        assertThat(body.get("correlationId")).isNotNull();
        assertThat(body.get("timestamp")).isNotNull();
    }
}
