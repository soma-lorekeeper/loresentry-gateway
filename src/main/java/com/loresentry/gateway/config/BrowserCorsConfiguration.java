package com.loresentry.gateway.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
public class BrowserCorsConfiguration {
    @Bean
    public CorsConfiguration browserCors(BrowserProperties browser) {
        var cors=new CorsConfiguration() {
            @Override public String checkOrigin(String origin) {
                return browser.frontendOrigin().equals(origin) ? origin : null;
            }
        };
        cors.setAllowedOrigins(List.of(browser.frontendOrigin()));
        cors.setAllowedMethods(List.of("GET","HEAD","POST","PATCH","PUT","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type","X-LS-CSRF","If-Match","If-None-Match","X-Save-Id"));
        cors.setExposedHeaders(List.of("Location"));
        cors.setAllowCredentials(true);cors.setMaxAge(3600L);
        return cors;
    }
    @Bean
    public FilterRegistrationBean<CorsFilter> browserCorsFilter(CorsConfiguration browserCors) {
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",browserCors);
        var registration=new FilterRegistrationBean<CorsFilter>(new CorsFilter(source) {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                if (java.util.Collections.list(request.getHeaders("Origin")).size() > 1) {
                    response.setStatus(403);response.addHeader("Vary","Origin");return;
                }
                super.doFilterInternal(request,response,chain);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+10);
        return registration;
    }
}
