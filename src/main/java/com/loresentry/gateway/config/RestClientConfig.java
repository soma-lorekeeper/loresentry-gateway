package com.loresentry.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient graphRagRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return builder
                .baseUrl(properties.graphRag().baseUrl())
                .build();
    }
}
