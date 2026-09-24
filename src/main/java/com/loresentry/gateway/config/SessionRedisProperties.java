package com.loresentry.gateway.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("loresentry.session-redis")
public record SessionRedisProperties(String host,int port,String username,String password,boolean tls) {
    @Override public String toString() { return "SessionRedisProperties[redacted]"; }
}
