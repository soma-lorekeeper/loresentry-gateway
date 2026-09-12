package com.loresentry.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loresentry.upstream")
public record UpstreamProperties(
        Service graphRag,
        Service aiChat,
        Service authentication,
        Service content) {

    public record Service(String baseUrl) {
    }
}
