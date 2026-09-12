package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.UpstreamClient;

import org.springframework.stereotype.Component;

@Component
public class UpstreamRelay {

    public Map<String, Object> describe(UpstreamClient client) {
        return Map.of(
                "service", "gateway-api",
                "thread", Thread.currentThread().toString(),
                "upstream", Map.of(client.name(), client.describe()));
    }
}
