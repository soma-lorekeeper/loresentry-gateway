package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.loresentry.gateway.security.JwtTestTokens.*;
import com.loresentry.gateway.config.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class AccessTokenFilterTest {
    AtomicInteger calls=new AtomicInteger();MockMvc mvc;
    SessionVerifier sessions=org.mockito.Mockito.mock(SessionVerifier.class);
    @RestController class Protected {
        @GetMapping("/projects") Object projects(@CurrentUser UUID user,HttpServletRequest request) {
            calls.incrementAndGet();assertThat(request.getHeader("x-user-id")).isNull();
            assertThat(java.util.Collections.list(request.getHeaders("X-User-Id"))).isEmpty();
            return java.util.Map.of("user",user.toString());
        }
        @PostMapping({"/auth/tokens/refresh","/auth/tokens/revoke"}) void tokens(){calls.incrementAndGet();}
        @GetMapping({"/auth/oauth/google/prepare","/auth/oauth/google/callback"}) void oauth(){calls.incrementAndGet();}
    }
    @BeforeEach void setup() {
        var cc=new BrowserCorsConfiguration();var cors=cc.browserCors(new BrowserProperties("http://localhost:3000","http://localhost:8000","http://localhost:3000/login",false));
        mvc=MockMvcBuilders.standaloneSetup(new Protected()).setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .addFilters(new CsrfFilter(cors),cc.browserCorsFilter(cors).getFilter(),new AccessTokenFilter(verifier(),new CookieSettings("ls_at","ls_rt","ls_oauth",false),sessions)).build();
    }
    @Test void externalIdentityAloneNeverReachesController() throws Exception {
        mvc.perform(get("/projects").header("X-User-Id",USER))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCESS_TOKEN_MISSING"))
            .andExpect(jsonPath("$.next_action").value("REFRESH")).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(calls).hasValue(0);
    }
    @Test void signedSubjectReplacesDuplicateExternalHeadersAndDoesNotNeedRefreshCookie() throws Exception {
        mvc.perform(get("/projects").cookie(new Cookie("ls_at",token(c->{})))
            .header("x-user-id",UUID.randomUUID(),UUID.randomUUID()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user").value(USER.toString()));
        assertThat(calls).hasValue(1);
    }
    @Test void duplicateAccessCookieIsInvalidAndCsrfStillPrecedesInvalidToken() throws Exception {
        mvc.perform(get("/projects").cookie(new Cookie("ls_at",token(c->{})),new Cookie("ls_at",token(c->{}))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
        mvc.perform(post("/projects").cookie(new Cookie("ls_at","invalid"))).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(calls).hasValue(0);
    }
    @Test void onlyExactAuthEntrypointsBypassAccessToken() throws Exception {
        for(String path:java.util.List.of("/auth/tokens/refresh","/auth/tokens/revoke"))
            mvc.perform(post(path).header("Origin","http://localhost:3000").header("X-LS-CSRF","1")).andExpect(status().isOk());
        mvc.perform(get("/auth/oauth/google/prepare")).andExpect(status().isOk());
        mvc.perform(get("/auth/oauth/google/callback")).andExpect(status().isOk());
        mvc.perform(get("/auth/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/unknown")).andExpect(status().isUnauthorized());
        assertThat(calls).hasValue(4);
    }
    @Test void sessionFailureStopsInternalCallsAndPreservesCookies() throws Exception {
        for(var reason:java.util.List.of(SecurityFailure.Reason.SESSION_INVALID,SecurityFailure.Reason.SESSION_UNAVAILABLE)) {
            org.mockito.Mockito.doThrow(new SecurityFailure(reason)).when(sessions).verify(org.mockito.ArgumentMatchers.any());
            mvc.perform(get("/projects").cookie(new Cookie("ls_at",token(c->{}))))
                .andExpect(status().is(reason.status)).andExpect(jsonPath("$.code").value(reason.name()))
                .andExpect(header().doesNotExist("Set-Cookie"));
        }
        assertThat(calls).hasValue(0);
    }
}
