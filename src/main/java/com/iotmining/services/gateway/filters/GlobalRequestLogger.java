package com.iotmining.services.gateway.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Structured access log for every request the gateway proxies - method,
 * path, resolved downstream status, and latency. Previously a bare
 * System.out.println of just the URI, which didn't go through SLF4J/the
 * configured Logback appender at all (so it bypassed log levels, JSON
 * formatting, and any future log shipping) and carried no status/timing
 * information - useless for actually diagnosing a slow or failing route.
 *
 * Micrometer Tracing (already configured - see application.yml's
 * management.tracing) injects trace/span id into the log MDC automatically
 * via Boot's default Logback pattern, so those show up in this line too
 * without anything extra here.
 */
@Component
@Slf4j
public class GlobalRequestLogger implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNanos = System.nanoTime();

        return chain.filter(exchange)
                .doOnSuccess(v -> logCompletion(exchange, startNanos, null))
                .doOnError(ex -> logCompletion(exchange, startNanos, ex));
    }

    private void logCompletion(ServerWebExchange exchange, long startNanos, Throwable error) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        var request = exchange.getRequest();
        var statusCode = exchange.getResponse().getStatusCode();
        Integer status = statusCode != null ? statusCode.value() : null;

        if (error != null) {
            log.warn("{} {} -> error after {}ms: {}",
                    request.getMethod(), request.getPath(), durationMs, error.getMessage());
        } else {
            log.info("{} {} -> {} in {}ms", request.getMethod(), request.getPath(), status, durationMs);
        }
    }

    @Override
    public int getOrder() {
        return -1; // Run early - wraps the whole downstream filter chain
    }
}
