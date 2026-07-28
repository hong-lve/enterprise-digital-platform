package com.company.dataops.gateway.filter;

import com.company.dataops.gateway.config.GatewayProperties;
import java.time.Instant;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * First-pass edge guard for externally exposed data APIs.
 *
 * This deliberately performs only cheap validation and header normalization.
 * The data-service-platform remains the authority for app/API authorization,
 * field-level permissions, and query limits.
 */
@Component
public class OpenApiAuthFilter implements GlobalFilter, Ordered {
    private static final String APP_KEY = "X-App-Key";
    private static final String TIMESTAMP = "X-Timestamp";
    private static final String NONCE = "X-Nonce";
    private static final String SIGNATURE = "X-Signature";

    private final GatewayProperties properties;

    public OpenApiAuthFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith("/openapi/") || path.equals("/openapi/health")) {
            return chain.filter(exchange);
        }

        String appKey = exchange.getRequest().getHeaders().getFirst(APP_KEY);
        String timestamp = exchange.getRequest().getHeaders().getFirst(TIMESTAMP);
        String nonce = exchange.getRequest().getHeaders().getFirst(NONCE);
        String signature = exchange.getRequest().getHeaders().getFirst(SIGNATURE);
        if (isBlank(appKey) || isBlank(timestamp) || isBlank(nonce)
            || (properties.isRequireSignature() && isBlank(signature))) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        if (!timestampLooksFresh(timestamp)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        ServerWebExchange forwarded = exchange.mutate()
            .request(builder -> builder.header("X-Gateway-Checked", "true"))
            .build();
        return chain.filter(forwarded);
    }

    private boolean timestampLooksFresh(String timestamp) {
        try {
            long epochMillis = Long.parseLong(timestamp);
            long delta = Math.abs(Instant.now().toEpochMilli() - epochMillis);
            return delta <= properties.getTimestampSkew().toMillis();
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
