package com.loresentry.gateway.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("loresentry.jwt")
public record JwtProperties(String publicKey,String keyId) {}
