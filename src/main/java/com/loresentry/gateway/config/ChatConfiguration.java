package com.loresentry.gateway.config;

import java.time.Duration;
import com.loresentry.gateway.application.ChatAllowlist;
import com.loresentry.gateway.client.chat.ChatStreamTransport;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {
    public static final Duration STREAM_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration STREAM_READ_TIMEOUT = Duration.ofSeconds(45);
    public static final int STREAM_CONNECTIONS = 200;

    @Bean
    public ChatAllowlist chatAllowlist(@Value("${loresentry.chat.allowed-users:}") String allowedUsers) {
        return ChatAllowlist.parse(allowedUsers);
    }

    @Bean(destroyMethod = "close")
    public ChatStreamTransport chatStreamTransport(UpstreamProperties upstream) {
        return new ChatStreamTransport(streamHttpClient(), upstream.aiChat().baseUrl());
    }

    public static CloseableHttpClient streamHttpClient() {
        return streamHttpClient(STREAM_CONNECT_TIMEOUT, STREAM_READ_TIMEOUT, STREAM_CONNECTIONS);
    }

    public static CloseableHttpClient streamHttpClient(Duration connect, Duration read, int connections) {
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnPerRoute(connections).setMaxConnTotal(connections)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.of(connect))
                        .setSocketTimeout(Timeout.of(read)).build()).build();
        return HttpClients.custom().setConnectionManager(pool).setConnectionReuseStrategy((request, response, context) -> false)
                .disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(connect)).setResponseTimeout(Timeout.of(read)).build())
                .build();
    }
}
