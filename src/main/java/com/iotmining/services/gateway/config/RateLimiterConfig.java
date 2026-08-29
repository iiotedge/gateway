package com.iotmining.services.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * RequestRateLimiter (already referenced in route filters, on more routes
 * now than before) had no KeyResolver bean anywhere in this codebase -
 * without one, Spring Cloud Gateway falls back to its own default
 * PrincipalNameKeyResolver, which resolves via
 * exchange.getPrincipal().map(Principal::getName) and, per its own
 * javadoc, treats an empty Mono (no principal - i.e. any unauthenticated/
 * public request) as EMPTY_KEY, meaning every unauthenticated caller
 * shares one single rate-limit bucket rather than being limited
 * individually. That's the opposite of useful for the public routes
 * (/auth/**, /public/**) that don't require a token in the first place.
 *
 * This resolver: authenticated requests key by the JWT subject (so a
 * given user's own limit doesn't get consumed by everyone else, and one
 * user hammering the API doesn't throttle anyone but themselves);
 * unauthenticated requests key by remote address instead of collapsing
 * into one shared bucket.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver keyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .switchIfEmpty(Mono.fromSupplier(() -> resolveRemoteAddress(exchange.getRequest().getRemoteAddress())));
    }

    private String resolveRemoteAddress(InetSocketAddress remoteAddress) {
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }
}
