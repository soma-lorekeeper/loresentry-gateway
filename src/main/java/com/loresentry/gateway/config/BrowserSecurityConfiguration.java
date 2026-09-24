package com.loresentry.gateway.config;

import com.loresentry.gateway.security.CsrfFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;

@Configuration(proxyBeanMethods=false)
public class BrowserSecurityConfiguration {
    @Bean
    public FilterRegistrationBean<CsrfFilter> csrfFilter(CorsConfiguration browserCors) {
        var registration=new FilterRegistrationBean<>(new CsrfFilter(browserCors));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+5);
        return registration;
    }
}
