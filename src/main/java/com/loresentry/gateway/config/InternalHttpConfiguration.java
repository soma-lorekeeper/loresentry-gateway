package com.loresentry.gateway.config;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

@Configuration(proxyBeanMethods = false)
public class InternalHttpConfiguration {
    @Bean(destroyMethod = "close")
    public CloseableHttpClient internalHttpClient(
            @Value("${spring.http.clients.connect-timeout:2s}") Duration connect,
            @Value("${spring.http.clients.read-timeout:10s}") Duration read,
            @Value("${loresentry.http.max-connections:25}") int maxConnections,
            @Value("${loresentry.http.max-connections-per-route:5}") int maxConnectionsPerRoute) {
        return HttpClients.custom().setConnectionManager(pool(connect, read, maxConnections, maxConnectionsPerRoute))
                .disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connect.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(read.toMillis())).build()).build();
    }

    static PoolingHttpClientConnectionManager pool(Duration connect, Duration read, int maxConnections,
            int maxConnectionsPerRoute) {
        return PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnectionsPerRoute).setDefaultConnectionConfig(
                        ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(connect.toMillis()))
                                .setSocketTimeout(Timeout.ofMilliseconds(read.toMillis())).build()).build();
    }

    @Bean
    RestClientCustomizer internalTransport(CloseableHttpClient internalHttpClient) {
        return builder -> builder.requestFactory(new HttpComponentsClientHttpRequestFactory(internalHttpClient));
    }
}
