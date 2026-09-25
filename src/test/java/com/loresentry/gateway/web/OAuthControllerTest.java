package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import com.loresentry.gateway.application.LoginService;
import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.web.auth.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OAuthControllerTest {
    static final Instant NOW=Instant.parse("2026-09-24T00:00:00Z");
    LoginService login;MockMvc mvc;
    LoginService.Session session(){return new LoginService.Session(new SessionId("A".repeat(43)),NOW.plusSeconds(1209600));}
    @BeforeEach void setup(){setup(false);}
    void setup(boolean prod) {
        login=mock(LoginService.class);String prefix=prod?"__Host-":"";
        var names=new CookieSettings(prefix+"ls_oauth",prod);
        var browser=new BrowserProperties(prod?"https://loresentry.com":"http://localhost:3000",prod?"https://api.loresentry.com":"http://localhost:8000",prod?"https://loresentry.com/login":"http://localhost:3000/login",prod);
        mvc=MockMvcBuilders.standaloneSetup(new OAuthController(login,new AuthCookies(names,Clock.fixed(NOW,ZoneOffset.UTC)),names,browser))
            .addFilters(new SensitiveResponseFilter()).build();
    }
    @Test void prepareSetsOnlyTemporaryCookieAndGoogleLocation() throws Exception {
        when(login.prepare()).thenReturn(new LoginService.Preparation("https://accounts.google.com/o/oauth2/v2/auth?state=auth-state","request-id",NOW.plusSeconds(300),LoginService.Result.SUCCESS));
        var response=mvc.perform(get("/auth/oauth/google/prepare").param("returnUrl","https://evil.test"))
            .andExpect(status().isFound()).andExpect(header().string("Location","https://accounts.google.com/o/oauth2/v2/auth?state=auth-state"))
            .andExpect(header().string("Cache-Control","no-store")).andExpect(content().string("")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).singleElement().asString().contains("ls_oauth=request-id","SameSite=Lax","HttpOnly").doesNotContain("ls_at","ls_rt");
    }
    @Test void callbackIgnoresHostReturnUrlAndProviderDescription() throws Exception {
        when(login.callback("request-id","secret-state","secret-code",null)).thenReturn(new LoginService.CallbackResult(session(),LoginService.Result.SUCCESS,true));
        var response=mvc.perform(get("/auth/oauth/google/callback").cookie(new Cookie("ls_oauth","request-id"))
            .param("state","secret-state").param("code","secret-code").param("returnUrl","https://evil.test").param("error_description","private")
            .header("Host","evil.test").header("X-Forwarded-Host","evil.test"))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","http://localhost:3000/login?result=success"))
            .andExpect(header().string("Referrer-Policy","no-referrer")).andExpect(content().string("")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(s->s.startsWith("ls_oauth=")&&s.contains("Max-Age=0"));
        assertThat(response.getHeader("Location")).doesNotContain("secret","request-id","private","evil");
    }
    @ParameterizedTest @CsvSource({"CANCELLED,true,cancelled,1","INVALID,false,invalid,0","UNAVAILABLE,null,unavailable,0","FAILED,true,failed,1"})
    void failureKeepsAuthenticationCookiesAndOnlyDeletesConsumedOAuth(String result,String consumed,String expected,int count) throws Exception {
        when(login.callback(any(),any(),any(),any())).thenReturn(new LoginService.CallbackResult(null,LoginService.Result.valueOf(result),"null".equals(consumed)?null:Boolean.valueOf(consumed)));
        var response=mvc.perform(get("/auth/oauth/google/callback").cookie(new Cookie("ls_oauth","request-id"),new Cookie("ls_session","existing"))
            .param("state","state").param("error","access_denied"))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","http://localhost:3000/login?result="+expected)).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(count).allMatch(s->s.startsWith("ls_oauth="));
    }
    @Test void expiredSuccessSessionDoesNotPartiallyReplaceAuthentication() throws Exception {
        when(login.callback(any(),any(),any(),any())).thenReturn(new LoginService.CallbackResult(
            new LoginService.Session(new SessionId("A".repeat(43)),NOW),LoginService.Result.SUCCESS,true));
        var response=mvc.perform(get("/auth/oauth/google/callback").param("state","state").param("code","code"))
            .andExpect(header().string("Location","http://localhost:3000/login?result=failed")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).singleElement().asString().startsWith("ls_oauth=").contains("Max-Age=0");
    }
    @Test void rejectsDuplicateCallbackValuesAndHeadNeverCreatesOAuthState() throws Exception {
        mvc.perform(get("/auth/oauth/google/callback").param("state","one","two").param("code","code"))
            .andExpect(header().string("Location","http://localhost:3000/login?result=invalid")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(head("/auth/oauth/google/prepare")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(login);
    }
    @Test void productionResultIsFixedAndUnsafeAuthorizationUrlIsRejected() throws Exception {
        setup(true);
        when(login.prepare()).thenReturn(new LoginService.Preparation("https://evil.test/login","request",NOW.plusSeconds(300),LoginService.Result.SUCCESS));
        mvc.perform(get("/auth/oauth/google/prepare")).andExpect(status().isSeeOther())
            .andExpect(header().string("Location","https://loresentry.com/login?result=failed")).andExpect(header().doesNotExist("Set-Cookie"));
        when(login.callback(any(),any(),any(),any())).thenReturn(new LoginService.CallbackResult(session(),LoginService.Result.SUCCESS,true));
        var response=mvc.perform(get("/auth/oauth/google/callback").param("state","state").param("code","code"))
            .andExpect(header().string("Location","https://loresentry.com/login?result=success")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).allMatch(s->s.startsWith("__Host-")&&s.contains("Secure"));
    }
}
