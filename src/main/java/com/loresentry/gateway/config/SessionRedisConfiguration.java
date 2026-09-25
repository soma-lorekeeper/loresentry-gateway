package com.loresentry.gateway.config;

import java.time.Clock;
import java.time.Duration;
import io.lettuce.core.*;
import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class SessionRedisConfiguration {
    @Bean
    public RedisURI sessionWriter(SessionRedisProperties properties) {
        if(properties.host()==null||properties.host().isBlank()||properties.port()<1||properties.port()>65535
                ||properties.username()==null||properties.username().isBlank()||"default".equals(properties.username())
                ||properties.password()==null||properties.password().isBlank())
            throw new IllegalStateException("A dedicated BFF session credential and writer endpoint are required");
        return RedisURI.Builder.redis(properties.host(),properties.port()).withSsl(properties.tls())
                .withAuthentication(properties.username(),properties.password().toCharArray())
                .withTimeout(Duration.ofMillis(500)).build();
    }
    @Bean(destroyMethod="shutdown")
    public RedisClient sessionRedisClient() {
        var client=RedisClient.create();
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build()).build());
        return client;
    }
    @Bean public com.loresentry.gateway.client.session.OpaqueSessionReader opaqueSessionReader(RedisClient sessionRedisClient,RedisURI sessionWriter) {
        return new com.loresentry.gateway.client.session.OpaqueSessionReader(sessionRedisClient,sessionWriter);
    }
    @Bean public com.loresentry.gateway.security.OpaqueSessionVerifier opaqueSessions(com.loresentry.gateway.client.session.OpaqueSessionReader reader) {
        return new com.loresentry.gateway.security.OpaqueSessionVerifier(reader);
    }
}
