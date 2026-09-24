package com.loresentry.gateway.config;

import com.loresentry.gateway.client.UpstreamClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient graphRagRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return UpstreamClient.restClient(builder, properties.graphRag().baseUrl());
    }

    @Bean
    public RestClient aiChatRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return UpstreamClient.restClient(builder, properties.aiChat().baseUrl());
    }

    @Bean
    public RestClient authenticationRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return UpstreamClient.restClient(builder, properties.authentication().baseUrl());
    }

    @Bean
    public RestClient contentRestClient(RestClient.Builder builder, UpstreamProperties properties) {
        return UpstreamClient.restClient(builder, properties.content().baseUrl());
    }
}
