package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.loresentry.gateway.client.session.OpaqueSessionReader.VerifiedSession;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.web.auth.AuthCookies;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class SessionFilterTest {
    static final String ID="A".repeat(43);
    static final UUID USER=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-09-26T00:00:00Z");
    final OpaqueSessionVerifier verifier=mock(OpaqueSessionVerifier.class);
    final AtomicInteger calls=new AtomicInteger();
    MockMvc mvc;
    @RestController class Endpoints {
        @GetMapping("/projects") Object projects(@CurrentUser UUID user,HttpServletRequest request) {
            calls.incrementAndGet();assertThat(user).isEqualTo(USER);
            for(var name:List.of("X-User-Id","Cookie","Authorization")) {
                assertThat(request.getHeader(name)).isNull();
                assertThat(Collections.list(request.getHeaders(name))).isEmpty();
            }
            assertThat(request.getCookies()).isNull();return Map.of("user",user);
        }
        @GetMapping("/business-error") ResponseEntity<?> failure(@CurrentUser UUID user) {
            calls.incrementAndGet();return ResponseEntity.status(409).header("Cache-Control","public").body(Map.of("code","CONFLICT"));
        }
        @PostMapping("/auth/sessions/revoke") void logout(){calls.incrementAndGet();}
        @GetMapping({"/health","/auth/oauth/google/prepare","/auth/oauth/google/callback"}) void publicRoute(){calls.incrementAndGet();}
    }
    @BeforeEach void setup() {
        var config=new BrowserCorsConfiguration();
        var cors=config.browserCors(new BrowserProperties("http://localhost:3000","http://localhost:8000","http://localhost:3000/login",false));
        var names=new CookieSettings("ls_oauth",false);
        when(verifier.verify(ID)).thenReturn(new VerifiedSession(USER,NOW.plusSeconds(1209600)));
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
        mvc=MockMvcBuilders.standaloneSetup(new Endpoints()).setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addFilters(new CsrfFilter(cors),config.browserCorsFilter(cors).getFilter(),
                        new SessionFilter(verifier,names,new AuthCookies(names,Clock.fixed(NOW,ZoneOffset.UTC)))).build();
    }
    @Test void cookieAuthenticationRemovesAllExternalCredentialsBeforeDomainHandling() throws Exception {
        mvc.perform(get("/projects").cookie(new Cookie("ls_session",ID)).header("x-user-id",UUID.randomUUID(),UUID.randomUUID())
                .header("Authorization","Bearer attacker"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user").value(USER.toString()))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("ls_session="+ID)));
        verify(verifier).verify(ID);assertThat(calls).hasValue(1);
    }
    @Test void csrfPrecedesRedisAndDuplicateCookiesCannotAuthenticate() throws Exception {
        mvc.perform(post("/projects").cookie(new Cookie("ls_session",ID))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/projects").cookie(new Cookie("ls_session",ID),new Cookie("ls_session",ID)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(verifier);assertThat(calls).hasValue(0);
    }
    @Test void missingAndFailedSessionsNeverReachTheDomainOrChangeCookies() throws Exception {
        mvc.perform(get("/projects").header("X-User-Id",USER)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED")).andExpect(header().doesNotExist("Set-Cookie"));
        for(var reason:List.of(SecurityFailure.Reason.SESSION_INVALID,SecurityFailure.Reason.SESSION_UNAVAILABLE)) {
            doThrow(new SecurityFailure(reason)).when(verifier).verify(ID);
            mvc.perform(get("/projects").cookie(new Cookie("ls_session",ID))).andExpect(status().is(reason.status))
                    .andExpect(jsonPath("$.code").value(reason.name())).andExpect(header().doesNotExist("Set-Cookie"));
        }
        assertThat(calls).hasValue(0);
    }
    @Test void legacyCookiesAndBearerCredentialsCannotAuthenticate() throws Exception {
        mvc.perform(get("/projects").cookie(new Cookie("ls_at","signed.old.token"),new Cookie("ls_rt","signed.old.token"))
                .header("Authorization","Bearer signed.old.token")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED")).andExpect(header().doesNotExist("Set-Cookie"));
        verify(verifier).verify(null);verifyNoMoreInteractions(verifier);assertThat(calls).hasValue(0);
    }
    @Test void publicRoutesPreflightAndLogoutDoNotExtendSessions() throws Exception {
        for(var path:List.of("/health","/auth/oauth/google/prepare","/auth/oauth/google/callback"))
            mvc.perform(get(path).cookie(new Cookie("ls_session",ID))).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/auth/sessions/revoke").header("Origin","http://localhost:3000").header("X-LS-CSRF","1")
                .cookie(new Cookie("ls_session",ID))).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(options("/projects").header("Origin","http://localhost:3000").header("Access-Control-Request-Method","GET"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(verifier);
    }
    @Test void aDomainErrorStillRenewsTheSameIdAndForbidsCaching() throws Exception {
        mvc.perform(get("/business-error").cookie(new Cookie("ls_session",ID))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("ls_session="+ID)));
    }
    @Test void invalidExpiryFailsBeforeTheDomainAndPreservesCookies() throws Exception {
        when(verifier.verify(ID)).thenReturn(new VerifiedSession(USER,NOW));
        mvc.perform(get("/projects").cookie(new Cookie("ls_session",ID))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SESSION_UNAVAILABLE")).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(calls).hasValue(0);
    }
}
