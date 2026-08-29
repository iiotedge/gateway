package com.iotmining.services.gateway.filters;

import lombok.Getter;
import lombok.Setter;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Named gateway filter factory (referenced as filter "ParamBlocker" in a
 * route's YAML) - blocks a request when a given query param equals a given
 * value. Not currently wired into any route, but kept available as a
 * quick, config-only way to kill a specific traffic pattern without a
 * deploy (e.g. a known-bad client sending a diagnostic/debug flag).
 *
 * Config previously declared paramName/blockValue as private fields with
 * no getters/setters despite a "// Getters & Setters" comment - Spring
 * Cloud Gateway binds route filter args onto this Config object via
 * reflection (Spring's DataBinder), which requires real accessors; as
 * written, wiring this into any route's YAML would have failed at startup
 * the moment it was tried.
 */
@Component
public class ParamBlockerFilter extends AbstractGatewayFilterFactory<ParamBlockerFilter.Config> {

    public ParamBlockerFilter() {
        super(Config.class);
    }

    @Getter
    @Setter
    public static class Config {
        private String paramName;
        private String blockValue;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String paramValue = exchange.getRequest().getQueryParams().getFirst(config.getParamName());
            if (config.getBlockValue().equals(paramValue)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }
}
