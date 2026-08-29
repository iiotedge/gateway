package com.iotmining.services.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reached via CircuitBreaker's fallbackUri (forward:/fallback) when a
 * downstream service is failing/timing out/open-breaker. forward:/fallback
 * preserves the original request's HTTP method, so this handles all of
 * them identically rather than only GET - a failed POST/PUT/DELETE would
 * otherwise 405 here instead of surfacing the real 503.
 *
 * Returns real JSON (not a bare string) so callers can parse it the same
 * way they'd parse any other API error response, with a correlation id
 * they can quote back for support/debugging.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback")
    public ResponseEntity<Map<String, Object>> fallback(ServerWebExchange exchange) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "service_unavailable");
        body.put("message", "The requested service is temporarily unavailable. Please retry shortly.");
        body.put("path", exchange.getRequest().getPath().value());
        body.put("correlationId", UUID.randomUUID().toString());
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(body);
    }
}
