package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

public final class ChatStreamTransport implements AutoCloseable {
    private final CloseableHttpClient http;
    private final DefaultUriBuilderFactory uris;

    public ChatStreamTransport(CloseableHttpClient http, String baseUrl) {
        this.http = http;
        this.uris = new DefaultUriBuilderFactory(baseUrl);
    }
    CloseableHttpClient http() { return http; }
    URI uri(String template, Map<String, ?> variables) { return uris.expand(template, variables); }
    @Override public void close() throws IOException { http.close(); }
}
