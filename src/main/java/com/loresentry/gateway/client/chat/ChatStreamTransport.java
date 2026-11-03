package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.function.BiConsumer;
import com.loresentry.gateway.logging.TracePropagation;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

public final class ChatStreamTransport implements AutoCloseable {
    private final CloseableHttpClient http;
    private final DefaultUriBuilderFactory uris;
    /** SSE 요청은 RestClient를 거치지 않으므로 traceparent를 여기서 직접 싣는다. */
    private final TracePropagation trace;

    public ChatStreamTransport(CloseableHttpClient http, String baseUrl) {
        this(http, baseUrl, TracePropagation.NONE);
    }
    public ChatStreamTransport(CloseableHttpClient http, String baseUrl, TracePropagation trace) {
        this.http = http;
        this.uris = new DefaultUriBuilderFactory(baseUrl);
        this.trace = trace;
    }
    CloseableHttpClient http() { return http; }
    URI uri(String template, Map<String, ?> variables) { return uris.expand(template, variables); }
    /** 지금 span의 W3C 헤더를 요청에 넣는다. */
    void trace(BiConsumer<String, String> header) { trace.inject(header); }
    @Override public void close() throws IOException { http.close(); }
}
