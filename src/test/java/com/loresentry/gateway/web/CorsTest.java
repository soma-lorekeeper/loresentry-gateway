package com.loresentry.gateway.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@com.loresentry.gateway.LocalTestEnvironment
@SpringBootTest
class CorsTest {

    private MockMvc mockMvc;

    @Autowired
    void setUp(WebApplicationContext context, org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.CorsFilter> browserCorsFilter) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(browserCorsFilter.getFilter()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000"})
    void preflightIsAllowedFor(String origin) throws Exception {
        mockMvc.perform(options("/content")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://loresentry.com", "https://app.loresentry.com", "http://localhost:5173", "http://127.0.0.1:3000",
            "http://localhost:3000/", "null", "http://localhost:3000.evil.test",
            "https://evil.com",
            "https://loresentry.com.evil.com",
            "http://loresentry.com",
            "https://loresentry.evil.com"
    })
    void preflightIsRejectedFor(String origin) throws Exception {
        mockMvc.perform(options("/content")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightAdvertisesTheWriteMethodsAndMaxAge() throws Exception {
        mockMvc.perform(options("/content")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        "GET,HEAD,POST,PATCH,PUT,DELETE,OPTIONS"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    @Test
    void actualRequestCarriesTheAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/health").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Vary",
                        org.hamcrest.Matchers.containsString("Origin")));
    }

    @Test
    void requestWithoutOriginIsUntouched() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void rejectsUncontractedHeaderAndAllowsContentHeaders() throws Exception {
        mockMvc.perform(options("/projects").header("Origin","http://localhost:3000")
            .header("Access-Control-Request-Method","PUT").header("Access-Control-Request-Headers","X-User-Id"))
            .andExpect(status().isForbidden());
        mockMvc.perform(options("/projects").header("Origin","http://localhost:3000")
            .header("Access-Control-Request-Method","PUT")
            .header("Access-Control-Request-Headers","Content-Type,X-LS-CSRF,If-Match,If-None-Match,X-Save-Id"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Expose-Headers","Location"));
    }
    @Test void productionOriginDoesNotAllowSubdomainsOrLocalhost() {
        var config=new com.loresentry.gateway.config.BrowserCorsConfiguration().browserCors(
            new com.loresentry.gateway.config.BrowserProperties("https://loresentry.com","https://api.loresentry.com","https://loresentry.com/login",true));
        org.assertj.core.api.Assertions.assertThat(config.checkOrigin("https://loresentry.com")).isEqualTo("https://loresentry.com");
        for(String origin:java.util.List.of("https://app.loresentry.com","http://localhost:3000","https://loresentry.com/","https://loresentry.com.evil.test"))
            org.assertj.core.api.Assertions.assertThat(config.checkOrigin(origin)).isNull();
    }
    @Test void rejectsMultipleOriginHeaders() throws Exception {
        mockMvc.perform(options("/projects").header("Origin","http://localhost:3000","https://evil.test")
            .header("Access-Control-Request-Method","GET")).andExpect(status().isForbidden());
    }
}
