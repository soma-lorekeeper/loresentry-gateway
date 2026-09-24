package com.loresentry.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("loresentry.browser")
public record BrowserProperties(String frontendOrigin, String publicOrigin, String loginRedirect, boolean secureCookies) {}
