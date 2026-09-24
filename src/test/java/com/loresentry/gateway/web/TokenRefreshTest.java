package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import jakarta.servlet.http.Cookie;
import com.loresentry.gateway.application.TokenService;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.security.CsrfFilter;
import com.loresentry.gateway.web.auth.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class TokenRefreshTest {
    MockRestServiceServer server;MockMvc mvc;
    @BeforeEach void setup(){
        var builder=RestClient.builder().baseUrl("http://auth.test");var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);server=mocks.getServer();
        var names=new CookieSettings("ls_at","ls_rt","ls_oauth",false);
        var controller=new TokenController(new TokenService(new AuthApiClient(builder.build())),
            new AuthCookies(names,Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"),ZoneOffset.UTC)),names);
        var cc=new BrowserCorsConfiguration();var cors=cc.browserCors(new BrowserProperties("http://localhost:3000","http://localhost:8000","http://localhost:3000/login",false));
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new AuthExceptionHandler())
            .addFilters(new SensitiveResponseFilter(),new CsrfFilter(cors),cc.browserCorsFilter(cors).getFilter()).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refresh() {
        return post("/auth/tokens/refresh").header("Origin","http://localhost:3000").header("X-LS-CSRF","1");
    }
    @Test void usesOnlyRefreshCookieAndReturns204WithBothCookies() throws Exception {
        server.expect(requestTo("http://auth.test/auth/tokens/refresh")).andExpect(method(HttpMethod.POST))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"refresh_token\":\"cookie-token\"}"))
            .andExpect(headerDoesNotExist("Cookie")).andExpect(headerDoesNotExist("Authorization")).andExpect(headerDoesNotExist("X-User-Id"))
            .andRespond(withSuccess("""
                {"access_token":"new.access.signature","access_expires_at":"2026-09-24T00:15:00Z",
                 "refresh_token":"new.refresh.signature","refresh_expires_at":"2026-10-08T00:00:00Z"}
                """,MediaType.APPLICATION_JSON));
        var response=mvc.perform(refresh().cookie(new Cookie("ls_rt","cookie-token"),new Cookie("ls_at","invalid"))
            .param("refresh_token","query-token").contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"body-token\"}"))
            .andExpect(status().isNoContent()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""))
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2);server.verify();
    }
    @Test void missingCookieNeverAcceptsQueryOrBodyAndCsrfStillRunsFirst() throws Exception {
        mvc.perform(refresh().param("refresh_token","query-token").contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"body-token\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_REJECTED")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/auth/tokens/refresh").cookie(new Cookie("ls_rt","token")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));server.verify();
    }
    @ParameterizedTest @CsvSource({"401,REFRESH_REJECTED,RELOGIN", "503,REFRESH_UNAVAILABLE,RETRY_LATER",
        "503,REFRESH_OUTCOME_UNKNOWN,RELOGIN", "500,INTERNAL_ERROR,NONE", "400,INVALID_REQUEST,NONE"})
    void knownFailuresPreserveContractButNeverModifyCookies(int status,String code,String action) throws Exception {
        server.expect(requestTo("http://auth.test/auth/tokens/refresh")).andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\""+code+"\",\"message\":\"private SQL detail\",\"next_action\":\""+action+"\"}"));
        var response=mvc.perform(refresh().cookie(new Cookie("ls_rt","token"))).andExpect(status().is(status))
            .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.next_action").value(action)).andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private SQL detail");server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"access_token\":false}",
        "{\"access_token\":\"a.b.c\",\"access_expires_at\":\"2026-09-24T00:00:00Z\",\"refresh_token\":\"d.e.f\",\"refresh_expires_at\":\"2026-10-08T00:00:00Z\"}"})
    void malformedOrExpiredSuccessMeansUnknownOutcome(String body) throws Exception {
        server.expect(requestTo("http://auth.test/auth/tokens/refresh")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        mvc.perform(refresh().cookie(new Cookie("ls_rt","token"))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("REFRESH_OUTCOME_UNKNOWN")).andExpect(jsonPath("$.next_action").value("RELOGIN"))
            .andExpect(header().doesNotExist("Set-Cookie"));server.verify();
    }
    @Test void responseLossMeansUnknownOutcomeAndNeverRetries() throws Exception {
        server.expect(requestTo("http://auth.test/auth/tokens/refresh")).andRespond(withException(new java.net.SocketTimeoutException("private")));
        mvc.perform(refresh().cookie(new Cookie("ls_rt","token"))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("REFRESH_OUTCOME_UNKNOWN")).andExpect(header().doesNotExist("Set-Cookie"));server.verify();
    }
}
