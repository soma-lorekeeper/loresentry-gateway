package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.loresentry.gateway.application.*;
import com.loresentry.gateway.client.auth.*;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.security.*;
import com.loresentry.gateway.web.auth.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(TermsController.class)
@Import({JsonConfiguration.class, TermsService.class, TermsControllerTest.Wiring.class})
class TermsControllerTest {
    static final Instant NOW=Instant.parse("2026-09-30T00:00:00Z");
    static final String ID="A".repeat(43), SESSION="B".repeat(42)+"A";
    final String version=UUID.randomUUID().toString();
    @Autowired MockMvc mvc;
    @MockitoBean AuthApiClient client;
    @MockitoBean OpaqueSessionVerifier verifier;
    @TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean CookieSettings names(){return new CookieSettings("ls_oauth",false);}
        @Bean AuthCookies cookies(CookieSettings names,Clock clock){return new AuthCookies(names,clock);}
        @Bean @Order(1) CsrfFilter csrf(){var cors=new CorsConfiguration();cors.setAllowedOrigins(List.of("http://localhost:3000"));return new CsrfFilter(cors);}
        @Bean @Order(2) SessionFilter session(OpaqueSessionVerifier verifier,CookieSettings names,AuthCookies cookies){return new SessionFilter(verifier,names,cookies);}
    }
    @Test void termsQuerySkipsSessionAndDoesNotRenewEitherCookie() throws Exception {
        when(client.terms(ID)).thenReturn(new AuthData.Terms(version,"1","Terms","Original\ntext",NOW,NOW.plusSeconds(1800)));
        mvc.perform(get("/auth/terms").cookie(new Cookie("ls_consent",ID),new Cookie("ls_session",SESSION)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.terms_version_id").value(version)).andExpect(jsonPath("$.consent_request_id").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(verifier);verify(client).terms(ID);
    }
    @Test void acceptanceReturnsOnly204AndSetsSessionAfterVerifiedSuccess() throws Exception {
        when(client.acceptTerms(ID,version)).thenReturn(new AuthData.AcceptedTerms(SESSION,NOW.plusSeconds(1209600)));
        var response=mvc.perform(post("/auth/terms/accept").cookie(new Cookie("ls_consent",ID))
                .header("Origin","http://localhost:3000").header("X-LS-CSRF","1")
                .contentType("application/json").content("{\"terms_version_id\":\""+version+"\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                .anyMatch(s->s.startsWith("ls_session="+SESSION)&&s.contains("HttpOnly")&&s.contains("SameSite=Strict"))
                .anyMatch(s->s.startsWith("ls_consent=")&&s.contains("Max-Age=0"));
        verifyNoInteractions(verifier);verify(client).acceptTerms(ID,version);
    }
    @Test void csrfAndConsentOnlyCredentialsCannotBypassProtection() throws Exception {
        mvc.perform(post("/auth/terms/accept").cookie(new Cookie("ls_consent",ID)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
        mvc.perform(get("/projects").cookie(new Cookie("ls_consent",ID))).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/terms/accept").cookie(new Cookie("ls_consent",ID))).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }
    @Test void absentDuplicateAndInvalidConsentClearOnlyPendingCookie() throws Exception {
        for(Cookie[] cookies:new Cookie[][]{{},{new Cookie("ls_consent",ID),new Cookie("ls_consent",ID)},{new Cookie("ls_consent","bad")}}) {
            var request=get("/auth/terms");
            if(cookies.length>0)request.cookie(cookies);
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("CONSENT_REQUEST_INVALID"))
                    .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.startsWith("ls_consent="),org.hamcrest.Matchers.containsString("Max-Age=0"))));
        }
        verifyNoInteractions(client,verifier);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"terms_version_id\":42}","{\"terms_version_id\":\"1-1-1-1-1\"}","{\"terms_version_id\":\"00000000-0000-0000-0000-000000000001\",\"consent_request_id\":\"injected\"}"})
    void invalidBodyNeverCallsAuth(String body) throws Exception {
        mvc.perform(post("/auth/terms/accept").cookie(new Cookie("ls_consent",ID)).header("Origin","http://localhost:3000").header("X-LS-CSRF","1")
                .contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(client,verifier);
    }
}
