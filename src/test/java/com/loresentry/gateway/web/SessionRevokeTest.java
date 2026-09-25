package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import jakarta.servlet.http.Cookie;
import com.loresentry.gateway.application.SessionService;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.security.CsrfFilter;
import com.loresentry.gateway.web.auth.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class SessionRevokeTest {
    MockRestServiceServer server;MockMvc mvc;
    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl("http://auth.test");var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);server=mocks.getServer();
        var names=new CookieSettings("__Host-ls_oauth",true);
        var cc=new BrowserCorsConfiguration();var cors=cc.browserCors(new BrowserProperties("https://loresentry.com","https://api.loresentry.com","https://loresentry.com/login",true));
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(new SessionService(new AuthApiClient(builder.build())),new AuthCookies(names,Clock.systemUTC()),names))
            .setControllerAdvice(new AuthExceptionHandler()).addFilters(new SensitiveResponseFilter(),new CsrfFilter(cors),cc.browserCorsFilter(cors).getFilter()).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder revoke(){return post("/auth/sessions/revoke").header("Origin","https://loresentry.com").header("X-LS-CSRF","1");}
    void cleared(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1).allMatch(s->s.contains("Max-Age=0")&&s.contains("Secure")&&s.contains("HttpOnly")&&s.contains("SameSite=Strict")&&s.contains("Path=/")&&!s.contains("Domain="));
        assertThat(response.getContentAsString()).doesNotContain("cookies_deleted","REFRESH","private detail");
    }
    @Test void noSessionCookieMeansNotRequestedEvenWithBodyId() throws Exception {
        var response=mvc.perform(revoke().param("session_id","query-token").contentType(MediaType.APPLICATION_JSON).content("{\"session_id\":\"body-token\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_revocation").value("not_requested")).andReturn().getResponse();
        cleared(response);server.verify();
    }
    @Test void confirmedRevocationDeletesTheSessionCookie() throws Exception {
        server.expect(requestTo("http://auth.test/auth/sessions/revoke")).andExpect(method(HttpMethod.POST))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"session_id\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"))
            .andRespond(withNoContent());
        var response=mvc.perform(revoke().cookie(new Cookie("__Host-ls_session","A".repeat(43))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_revocation").value("confirmed")).andReturn().getResponse();
        cleared(response);server.verify();
    }
    @ParameterizedTest @CsvSource({"400,INVALID_SESSION_ID,NONE,400,rejected", "503,REVOCATION_UNCONFIRMED,NONE,503,unconfirmed",
        "500,INTERNAL_ERROR,NONE,503,unconfirmed", "401,REFRESH_REJECTED,RELOGIN,503,unconfirmed"})
    void errorsStillDeleteTheCookie(int status,String code,String action,int external,String result) throws Exception {
        server.expect(requestTo("http://auth.test/auth/sessions/revoke")).andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\""+code+"\",\"message\":\"private detail\",\"next_action\":\""+action+"\"}"));
        var response=mvc.perform(revoke().cookie(new Cookie("__Host-ls_session","A".repeat(43)))).andExpect(status().is(external))
            .andExpect(jsonPath("$.session_revocation").value(result)).andExpect(jsonPath("$.next_action").value("NONE")).andReturn().getResponse();
        // Upstream diagnostics and browser deletion claims must not appear.
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1).allMatch(s->s.contains("Max-Age=0"));
        assertThat(response.getContentAsString()).doesNotContain("private detail","cookies_deleted");server.verify();
    }
    @Test void duplicateAndMalformedCookiesAreRejectedWithoutAuthCalls() throws Exception {
        for(var cookies:java.util.List.of(new Cookie[]{new Cookie("__Host-ls_session","bad")},
                new Cookie[]{new Cookie("__Host-ls_session","A".repeat(43)),new Cookie("__Host-ls_session","A".repeat(43))},
                new Cookie[]{new Cookie("__Host-ls_session","")})) {
            var response=mvc.perform(revoke().cookie(cookies)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.session_revocation").value("rejected"))
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_ID")).andReturn().getResponse();
            cleared(response);
        }
        server.verify();
    }
    @Test void logoutClearsPresentLegacyCookiesWithTheSameScope() throws Exception {
        var response=mvc.perform(revoke().cookie(new Cookie("__Host-ls_at","old"),new Cookie("__Host-ls_rt","old")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_revocation").value("not_requested")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(3).allMatch(cookie->cookie.contains("Max-Age=0")&&cookie.contains("Path=/")&&cookie.contains("Secure")&&!cookie.contains("Domain="));
        server.verify();
    }
    @Test void timeoutIsUnconfirmedButCsrfFailureDoesNotDeleteCookies() throws Exception {
        mvc.perform(post("/auth/sessions/revoke").cookie(new Cookie("__Host-ls_session","A".repeat(43))))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        server.expect(requestTo("http://auth.test/auth/sessions/revoke")).andRespond(withException(new java.net.SocketTimeoutException("private detail")));
        var response=mvc.perform(revoke().cookie(new Cookie("__Host-ls_session","A".repeat(43)))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.session_revocation").value("unconfirmed")).andReturn().getResponse();
        cleared(response);server.verify();
    }
}
