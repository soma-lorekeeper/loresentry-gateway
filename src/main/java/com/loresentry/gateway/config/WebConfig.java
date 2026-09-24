package com.loresentry.gateway.config;

import java.util.List;

import com.loresentry.gateway.identity.CurrentUserArgumentResolver;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CorsProperties cors;

    private final CurrentUserArgumentResolver currentUser;

    public WebConfig(CorsProperties cors, CurrentUserArgumentResolver currentUser) {
        this.cors = cors;
        this.currentUser = currentUser;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(toArray(cors.allowedOriginPatterns()))
                .allowedMethods(toArray(cors.allowedMethods()))
                .allowedHeaders(toArray(cors.allowedHeaders()))
                .exposedHeaders(toArray(cors.exposedHeaders()))
                .allowCredentials(cors.allowCredentials())
                .maxAge(cors.maxAge().toSeconds());
    }

    private static String[] toArray(List<String> values) {
        return values == null ? new String[0] : values.toArray(String[]::new);
    }
}
