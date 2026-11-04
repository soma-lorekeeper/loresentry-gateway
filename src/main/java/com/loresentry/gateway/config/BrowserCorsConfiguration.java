package com.loresentry.gateway.config;

import java.util.List;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.logging.OutcomeCorsProcessor;
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
        // X-Trace-Id: 브라우저가 문의할 때 보여 줄 trace ID(LOG_SCHEMA.md 7절)
        cors.setExposedHeaders(List.of("Location","X-Trace-Id"));
        cors.setAllowCredentials(true);cors.setMaxAge(3600L);
        return cors;
    }
    @Bean
    public FilterRegistrationBean<CorsFilter> browserCorsFilter(CorsConfiguration browserCors) {
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",browserCors);
        var filter=new CorsFilter(source) {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                if (java.util.Collections.list(request.getHeaders("Origin")).size() > 1) {
                    // Origin 헤더가 둘 이상이다. 거절 이유를 결과 줄(request completed)에 맡긴다. Origin 값은 남기지 않는다
                    AppLogger.current().addToOutcome(LogField.errorType("CORS_REJECTED"));
                    response.setStatus(403);response.addHeader("Vary","Origin");return;
                }
                super.doFilterInternal(request,response,chain);
            }
        };
        // 허용하지 않는 Origin·메서드·헤더를 Spring 기본 처리기가 403으로 거절할 때도 같은 이유를 결과 줄에 맡긴다. 응답은 기본 처리기 그대로다
        filter.setCorsProcessor(new OutcomeCorsProcessor());
        var registration=new FilterRegistrationBean<CorsFilter>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+10);
        return registration;
    }
}
