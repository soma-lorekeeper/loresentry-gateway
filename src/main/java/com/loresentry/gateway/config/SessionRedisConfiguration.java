package com.loresentry.gateway.config;

import java.time.Clock;
import java.time.Duration;
import io.lettuce.core.*;
import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
    /**
     * 모든 bean을 만든 직후, Tomcat이 요청을 받기 시작하기 전에 세션 저장소 연결을 한 번 데운다.
     * 그래서 새 파드의 첫 로그인 확인이 연결 초기화 비용 때문에 500 ms를 넘기지 않는다(OpaqueSessionReader.warmUp).
     */
    @Bean public SmartInitializingSingleton sessionStoreWarmUp(com.loresentry.gateway.client.session.OpaqueSessionReader opaqueSessionReader) {
        return () -> opaqueSessionReader.warmUp(Duration.ofSeconds(5));
    }
    @Bean public com.loresentry.gateway.security.OpaqueSessionVerifier opaqueSessions(com.loresentry.gateway.client.session.OpaqueSessionReader reader) {
        return new com.loresentry.gateway.security.OpaqueSessionVerifier(reader);
    }
}
