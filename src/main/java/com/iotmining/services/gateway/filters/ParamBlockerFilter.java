package com.iotmining.services.gateway.filters;


import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ParamBlockerFilter extends AbstractGatewayFilterFactory<ParamBlockerFilter.Config> {

    public ParamBlockerFilter() {
        super(Config.class);
    }

    public static class Config {
        private String paramName;
        private String blockValue;
        // Getters & Setters
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String paramValue = exchange.getRequest().getQueryParams().getFirst(config.paramName);
            if (config.blockValue.equals(paramValue)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }
}
