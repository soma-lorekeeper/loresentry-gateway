package com.loresentry.gateway.config;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class EnvironmentConfiguration {
    @Bean
    Object verifiedEnvironment(Environment environment, BrowserProperties browser, UpstreamProperties upstream,
            @Value("${loresentry.jwt.public-key}") String publicKey) {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        boolean local = profiles.contains("local"), prod = profiles.contains("prod");
        if (local == prod) throw new IllegalStateException("Activate exactly one of local or prod");
        String frontend = local ? "http://localhost:3000" : "https://loresentry.com";
        String api = local ? "http://localhost:8000" : "https://api.loresentry.com";
        if (!frontend.equals(browser.frontendOrigin()) || !api.equals(browser.publicOrigin())
                || !(frontend+"/login").equals(browser.loginRedirect()) || browser.secureCookies() != prod)
            throw new IllegalStateException("Browser settings do not match the active environment");
        for(var service: java.util.List.of(upstream.authentication(),upstream.content(),upstream.graphRag(),upstream.aiChat())) {
            var uri=URI.create(service.baseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalStateException("Invalid internal service address");
        }
        if(publicKey==null || publicKey.isBlank() || publicKey.contains("${"))
            throw new IllegalStateException("An environment-specific public key is required");
        return new Object();
    }
}
