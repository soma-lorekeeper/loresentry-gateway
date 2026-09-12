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

@SpringBootTest
class CorsTest {

    private MockMvc mockMvc;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://loresentry.com",
            "https://www.loresentry.com",
            "https://app.loresentry.com",
            "http://localhost:3000",
            "http://localhost:5173",
            "http://127.0.0.1:8080"
    })
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
                        .header("Origin", "https://loresentry.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        "GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD"))
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
}
