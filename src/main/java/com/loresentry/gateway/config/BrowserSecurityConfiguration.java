package com.loresentry.gateway.config;

import com.loresentry.gateway.security.CsrfFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;

@Configuration(proxyBeanMethods=false)
public class BrowserSecurityConfiguration {
    @Bean public java.time.Clock applicationClock() { return java.time.Clock.systemUTC(); }
    @Bean public CookieSettings cookieSettings(BrowserProperties browser) {
        String prefix=browser.secureCookies() ? "__Host-" : "";
        return new CookieSettings(prefix+"ls_at",prefix+"ls_rt",prefix+"ls_oauth",browser.secureCookies());
    }
    @Bean
    public FilterRegistrationBean<CsrfFilter> csrfFilter(CorsConfiguration browserCors) {
        var registration=new FilterRegistrationBean<>(new CsrfFilter(browserCors));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+5);
        return registration;
    }
    @Bean
    public FilterRegistrationBean<com.loresentry.gateway.web.auth.SensitiveResponseFilter> sensitiveResponses() {
        var registration=new FilterRegistrationBean<>(new com.loresentry.gateway.web.auth.SensitiveResponseFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+1);
        return registration;
    }
    @Bean
    public FilterRegistrationBean<com.loresentry.gateway.security.SessionFilter> sessionFilter(
            com.loresentry.gateway.security.OpaqueSessionVerifier verifier,CookieSettings names,
            com.loresentry.gateway.web.auth.AuthCookies cookies) {
        var registration=new FilterRegistrationBean<>(new com.loresentry.gateway.security.SessionFilter(verifier,names,cookies));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+20);
        return registration;
    }
}
