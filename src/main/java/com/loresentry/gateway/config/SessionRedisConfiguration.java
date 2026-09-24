package com.loresentry.gateway.config;

import java.time.Clock;
import java.time.Duration;
import io.lettuce.core.*;
import io.lettuce.core.protocol.ProtocolVersion;
import com.loresentry.gateway.client.session.SessionReader;
import com.loresentry.gateway.security.SessionVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class SessionRedisConfiguration {
    @Bean
    public RedisURI sessionWriter(SessionRedisProperties properties) {
        if(properties.host()==null||properties.host().isBlank()||properties.port()<1||properties.port()>65535
                ||properties.username()==null||properties.username().isBlank()||"default".equals(properties.username())
                ||properties.password()==null||properties.password().isBlank())
            throw new IllegalStateException("A dedicated BFF session reader credential and writer endpoint are required");
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
    @Bean public SessionReader sessionReader(RedisClient sessionRedisClient,RedisURI sessionWriter) {
        return new SessionReader(sessionRedisClient,sessionWriter);
    }
    @Bean public SessionVerifier sessions(SessionReader reader,Clock clock) { return new SessionVerifier(reader,clock); }
}
