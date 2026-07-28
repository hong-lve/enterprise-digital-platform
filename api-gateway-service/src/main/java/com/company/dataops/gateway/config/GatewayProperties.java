package com.company.dataops.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gateway")
public class GatewayProperties {
    private Duration timestampSkew = Duration.ofMinutes(5);
    private boolean requireSignature = false;

    public Duration getTimestampSkew() {
        return timestampSkew;
    }

    public void setTimestampSkew(Duration timestampSkew) {
        this.timestampSkew = timestampSkew;
    }

    public boolean isRequireSignature() {
        return requireSignature;
    }

    public void setRequireSignature(boolean requireSignature) {
        this.requireSignature = requireSignature;
    }
}
