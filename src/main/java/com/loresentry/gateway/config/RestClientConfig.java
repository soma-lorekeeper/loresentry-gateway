package com.loresentry.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient graphRagRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return builder.baseUrl(properties.graphRag().baseUrl()).build();
    }

    @Bean
    public RestClient aiChatRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return builder.baseUrl(properties.aiChat().baseUrl()).build();
    }

    @Bean
    public RestClient authenticationRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return builder.baseUrl(properties.authentication().baseUrl()).build();
    }

    @Bean
    public RestClient contentRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return builder.baseUrl(properties.content().baseUrl()).build();
    }
}
