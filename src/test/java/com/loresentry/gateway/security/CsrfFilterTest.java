package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import com.loresentry.gateway.config.*;

class CsrfFilterTest {
    AtomicInteger downstream=new AtomicInteger();
    MockMvc mvc;
    @RestController static class Endpoints {
        @GetMapping({"/auth/oauth/google/prepare","/auth/oauth/google/callback"}) String oauth(){return "oauth";}
    }
    @BeforeEach void setup() {
        var corsConfig=new BrowserCorsConfiguration();
        var cors=corsConfig.browserCors(new BrowserProperties("http://localhost:3000","http://localhost:8000","http://localhost:3000/login",false));
        mvc=MockMvcBuilders.standaloneSetup(new Endpoints()).addFilters(new CsrfFilter(cors),
            corsConfig.browserCorsFilter(cors).getFilter(),(request,response,chain)-> {
                if(((jakarta.servlet.http.HttpServletRequest)request).getRequestURI().startsWith("/auth/oauth/google/")) chain.doFilter(request,response);
                else { downstream.incrementAndGet();SecurityResponses.write((jakarta.servlet.http.HttpServletResponse)response,SecurityFailure.Reason.SESSION_REQUIRED); }
            }).build();
    }
    @ParameterizedTest @ValueSource(strings={"/projects","/projects","/auth/sessions/revoke","/auth/unknown"})
    void missingCsrfPrecedesMissingSession(String path) throws Exception {
        var result=mvc.perform(post(path).header("Origin","http://localhost:3000"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
            .andExpect(jsonPath("$.next_action").value("NONE")).andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000")).andReturn();
        assertThat(downstream).hasValue(0);assertThat(result.getRequest().getSession(false)).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"null","http://localhost:3001","https://app.loresentry.com","http://localhost:3000/","http://localhost:3000 https://evil.test",""})
    void rejectsBadOrigin(String origin) throws Exception {
        mvc.perform(post("/projects").header("Origin",origin).header("X-LS-CSRF","1"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(downstream).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={"0","true","1,1"," 1","1 ",""})
    void rejectsBadCsrfValue(String csrf) throws Exception {
        mvc.perform(post("/projects").header("Origin","http://localhost:3000").header("X-LS-CSRF",csrf))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(downstream).hasValue(0);
    }
    @Test void rejectsMissingOrDuplicateHeadersAndFormSubmission() throws Exception {
        mvc.perform(post("/projects").header("X-LS-CSRF","1")).andExpect(status().isForbidden());
        mvc.perform(post("/projects").header("Origin","http://localhost:3000","http://localhost:3000").header("X-LS-CSRF","1"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/projects").header("Origin","http://localhost:3000").header("X-LS-CSRF","1","1"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/projects").header("Origin","http://localhost:3000").contentType("application/x-www-form-urlencoded").content("X-LS-CSRF=1"))
            .andExpect(status().isForbidden());
        assertThat(downstream).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","PATCH","DELETE"})
    void validCsrfReachesAuthentication(String method) throws Exception {
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),"/projects")
            .header("Origin","http://localhost:3000").header("x-ls-csrf","1"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        assertThat(downstream).hasValue(1);
    }
    @Test void preflightStopsBeforeAuthenticationAndOAuthNavigationNeedsNoOrigin() throws Exception {
        mvc.perform(options("/projects").header("Origin","http://localhost:3000")
            .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","X-LS-CSRF"))
            .andExpect(status().isOk());
        mvc.perform(get("/auth/oauth/google/prepare")).andExpect(status().isOk());
        mvc.perform(get("/auth/oauth/google/callback")).andExpect(status().isOk());
        assertThat(downstream).hasValue(0);
    }
}
