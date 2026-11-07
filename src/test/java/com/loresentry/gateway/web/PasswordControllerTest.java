package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
@WebMvcTest(PasswordController.class)
@Import({JsonConfiguration.class,PasswordLoginService.class,PasswordControllerTest.Wiring.class})
class PasswordControllerTest {
    static final Instant NOW=Instant.parse("2026-11-07T00:00:00Z");
    static final String SESSION="A".repeat(43), CONSENT="B".repeat(42)+"A";
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
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder browser(String path,String body) {
        return post(path).header("Origin","http://localhost:3000").header("X-LS-CSRF","1").contentType("application/json").content(body);
    }
    @Test void signUpSetsTheSessionCookieAndReturnsOnlyTheStatus() throws Exception {
        when(client.signUp(new AuthData.SignUp("judge1","correct-horse-7")))
                .thenReturn(new AuthData.LoginSession("AUTHENTICATED",SESSION,null,NOW.plusSeconds(1209600),null));
        var response=mvc.perform(browser("/auth/password/signup","{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}")
                .cookie(new Cookie("ls_consent",CONSENT)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().json("{\"status\":\"AUTHENTICATED\"}",org.springframework.test.json.JsonCompareMode.STRICT))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(SESSION);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                .anyMatch(s->s.startsWith("ls_session="+SESSION)&&s.contains("HttpOnly")&&s.contains("SameSite=Strict")&&s.contains("Max-Age=1209600"))
                .anyMatch(s->s.startsWith("ls_consent=")&&s.contains("Max-Age=0"));
        verifyNoInteractions(verifier);
    }
    @Test void loginRequiringTermsSetsOnlyTheConsentCookie() throws Exception {
        when(client.passwordLogin(new AuthData.PasswordLogin("judge1","correct-horse-7")))
                .thenReturn(new AuthData.LoginSession("TERMS_REQUIRED",null,CONSENT,NOW.plusSeconds(1800),null));
        var response=mvc.perform(browser("/auth/password/login","{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"TERMS_REQUIRED\"}",org.springframework.test.json.JsonCompareMode.STRICT))
                .andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).singleElement().asString()
                .startsWith("ls_consent="+CONSENT).contains("HttpOnly","Max-Age=1800").doesNotContain("ls_session");
        verifyNoInteractions(verifier);
    }
    @ParameterizedTest @CsvSource({"409,LOGIN_ID_TAKEN,NONE","401,INVALID_CREDENTIALS,NONE","400,INVALID_LOGIN_ID,NONE","400,INVALID_PASSWORD,NONE","503,LOGIN_UNAVAILABLE,RESTART_LOGIN"})
    void knownAuthErrorsPassThroughWithoutCookies(int status,String code,String action) throws Exception {
        when(client.passwordLogin(any())).thenThrow(new AuthCallFailure(AuthCallFailure.Kind.CONTRACT,status,code,action,null,false));
        mvc.perform(browser("/auth/password/login","{\"login_id\":\"judge1\",\"password\":\"wrong-password-7\"}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.next_action").value(action))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wrong"))))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void transportFailuresAndBadUpstreamResponsesAreMapped() throws Exception {
        when(client.signUp(any())).thenThrow(AuthCallFailure.unavailable(true));
        mvc.perform(browser("/auth/password/signup","{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"));
        doReturn(new AuthData.LoginSession("AUTHENTICATED",SESSION,null,NOW,null)).when(client).signUp(any());
        mvc.perform(browser("/auth/password/signup","{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE")).andExpect(header().doesNotExist("Set-Cookie"));
    }
    @ParameterizedTest @ValueSource(strings={"","{}","{\"login_id\":\"judge1\"}","{\"login_id\":\"\",\"password\":\"x\"}","{\"login_id\":1,\"password\":\"x\"}","{\"login_id\":\"a\",\"password\":\"x\",\"extra\":1}"})
    void malformedBodiesNeverReachAuth(String body) throws Exception {
        mvc.perform(browser("/auth/password/login",body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(client);
    }
    @Test void csrfIsEnforcedAndTheRoutesSkipTheSessionFilter() throws Exception {
        mvc.perform(post("/auth/password/login").contentType("application/json").content("{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
        mvc.perform(get("/auth/password/login")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }
}
