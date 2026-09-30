package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.loresentry.gateway.application.*;
import com.loresentry.gateway.client.session.OpaqueSessionReader.VerifiedSession;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.security.*;
import com.loresentry.gateway.web.auth.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.cors.CorsConfiguration;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(AccountController.class)
@Import({JsonConfiguration.class, CurrentUserArgumentResolver.class, AccountDeletionControllerTest.Wiring.class})
class AccountDeletionControllerTest {
    static final Instant NOW=Instant.parse("2026-09-30T00:00:00Z");
    static final String SESSION="A".repeat(43);
    static final UUID USER=UUID.randomUUID();
    @Autowired MockMvc mvc;
    @MockitoBean OpaqueSessionVerifier verifier;
    @MockitoBean AccountService accounts;
    @MockitoBean AccountDeletionService deletions;
    @TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean CookieSettings names(){return new CookieSettings("__Host-ls_oauth",true);}
        @Bean AuthCookies cookies(CookieSettings names,Clock clock){return new AuthCookies(names,clock);}
        @Bean @Order(1) CsrfFilter csrf(){var cors=new CorsConfiguration();cors.setAllowedOrigins(List.of("https://loresentry.com"));return new CsrfFilter(cors);}
        @Bean @Order(2) SessionFilter session(OpaqueSessionVerifier verifier,CookieSettings names,AuthCookies cookies){return new SessionFilter(verifier,names,cookies);}
    }
    @BeforeEach void session() {when(verifier.verify(SESSION)).thenReturn(new VerifiedSession(USER,NOW.plusSeconds(1209600)));}
    MockHttpServletRequestBuilder deletion(String body) {
        return post("/auth/users/me/deletion").header("Origin","https://loresentry.com").header("X-LS-CSRF","1")
                .cookie(new Cookie("__Host-ls_session",SESSION),new Cookie("__Host-ls_at","legacy"),new Cookie("ls_rt","other-env"))
                .contentType("application/json").content(body);
    }
    @Test void successClearsTheSessionAndLegacyCookiesWithoutRenewal() throws Exception {
        var response=mvc.perform(deletion("{\"confirmation_email\":\"writer@example.test\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                .allMatch(s->s.contains("Max-Age=0")&&s.contains("Secure")&&s.contains("HttpOnly")&&s.contains("SameSite=Strict")&&s.contains("Path=/"))
                .anyMatch(s->s.startsWith("__Host-ls_session=;")).anyMatch(s->s.startsWith("__Host-ls_at=;"))
                .noneMatch(s->s.contains(SESSION));
        verify(deletions).delete(USER,"writer@example.test");
    }
    @ParameterizedTest @CsvSource({"400,ACCOUNT_CONFIRMATION_MISMATCH,NONE","503,ACCOUNT_DELETION_UNAVAILABLE,RETRY_LATER",
        "502,UPSTREAM_INVALID_RESPONSE,NONE","401,USER_CONTEXT_REQUIRED,RELOGIN","404,USER_NOT_FOUND,RELOGIN"})
    void failureKeepsTheSessionCookie(int status,String code,String action) throws Exception {
        doThrow(new AuthOperationFailure(status,code,action)).when(deletions).delete(USER,"writer@example.test");
        var response=mvc.perform(deletion("{\"confirmation_email\":\"writer@example.test\"}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.next_action").value(action))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        assertNotCleared(response);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","{\"confirmation_email\":null}","{\"confirmation_email\":42}",
        "{\"confirmation_email\":\"writer@example.test\",\"user_id\":\"x\"}","{\"confirmation_email\":\"a\"} {}",""})
    void malformedBodyIsRejectedBeforeAnyUpstreamCall(String body) throws Exception {
        var response=mvc.perform(deletion(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(jsonPath("$.next_action").value("NONE")).andReturn().getResponse();
        assertNotCleared(response);verifyNoInteractions(deletions);
    }
    @Test void csrfAndSessionAreRequired() throws Exception {
        mvc.perform(post("/auth/users/me/deletion").cookie(new Cookie("__Host-ls_session",SESSION)).contentType("application/json")
                .content("{\"confirmation_email\":\"writer@example.test\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(put("/auth/users/me/onboarding").cookie(new Cookie("__Host-ls_session",SESSION)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
        mvc.perform(post("/auth/users/me/deletion").header("Origin","https://loresentry.com").header("X-LS-CSRF","1")
                .contentType("application/json").content("{\"confirmation_email\":\"writer@example.test\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        verifyNoInteractions(deletions,accounts);
    }
    @Test void onboardingCompletionPassesCsrfAndSession() throws Exception {
        mvc.perform(put("/auth/users/me/onboarding").header("Origin","https://loresentry.com").header("X-LS-CSRF","1")
                .cookie(new Cookie("__Host-ls_session",SESSION))).andExpect(status().isNoContent());
        verify(accounts).completeOnboarding(USER);
    }
    private static void assertNotCleared(MockHttpServletResponse response) {
        assertThat(response.getHeaders("Set-Cookie")).noneMatch(s->s.contains("Max-Age=0"))
                .allMatch(s->s.startsWith("__Host-ls_session="+SESSION));
    }
}
