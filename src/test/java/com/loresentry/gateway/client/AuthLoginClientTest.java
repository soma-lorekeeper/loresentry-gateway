package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.application.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthLoginClientTest {
    MockRestServiceServer server;LoginService service;
    @BeforeEach void setup(){
        var builder=RestClient.builder().baseUrl("http://auth.test");var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        server=mocks.getServer();service=new LoginService(new AuthApiClient(builder.build()));
    }
    @Test void preparationUsesInternalPostWithoutUserOrBrowserCredentials() {
        server.expect(requestTo("http://auth.test/auth/oauth/google/prepare")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{}")).andExpect(headerDoesNotExist("Cookie")).andExpect(headerDoesNotExist("Authorization"))
            .andExpect(headerDoesNotExist("X-User-Id")).andRespond(withSuccess("""
                {"authorization_url":"https://accounts.google.com/o/oauth2/v2/auth?state=state","login_request_id":"request-id","expires_at":"2026-09-24T00:05:00Z"}
                """,MediaType.APPLICATION_JSON));
        var result=service.prepare();assertThat(result.result()).isEqualTo(LoginService.Result.SUCCESS);
        assertThat(result.requestId()).isEqualTo("request-id");assertThat(result.toString()).doesNotContain("state","request-id");server.verify();
    }
    @Test void callbackSendsSecretsOnlyInBodyAndPreservesConsumption() {
        server.expect(requestTo("http://auth.test/auth/oauth/google/callback")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"login_request_id\":\"request-id\",\"state\":\"state\",\"code\":\"code\",\"error\":null}"))
            .andExpect(headerDoesNotExist("Cookie")).andRespond(withSuccess("""
                {"access_token":"access.payload.signature","access_expires_at":"2026-09-24T00:15:00Z",
                 "refresh_token":"refresh.payload.signature","refresh_expires_at":"2026-10-08T00:00:00Z","login_request_consumed":true}
                """,MediaType.APPLICATION_JSON));
        var result=service.callback("request-id","state","code",null);
        assertThat(result.result()).isEqualTo(LoginService.Result.SUCCESS);assertThat(result.consumed()).isTrue();
        assertThat(result.tokens().accessToken()).isEqualTo("access.payload.signature");server.verify();
    }
    @ParameterizedTest @CsvSource({"400,OAUTH_LOGIN_DENIED,RESTART_LOGIN,true,CANCELLED",
        "400,OAUTH_REQUEST_INVALID,RESTART_LOGIN,false,INVALID", "503,LOGIN_UNAVAILABLE,RESTART_LOGIN,null,UNAVAILABLE",
        "401,OAUTH_IDENTITY_INVALID,RESTART_LOGIN,true,FAILED", "400,INTERNAL_ERROR,NONE,true,FAILED"})
    void mapsKnownErrorsWithoutLosingConsumed(int status,String code,String action,String consumed,String result) {
        server.expect(requestTo("http://auth.test/auth/oauth/google/callback"))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\""+code+"\",\"message\":\"private detail\",\"next_action\":\""+action+"\",\"login_request_consumed\":"+consumed+"}"));
        var response=service.callback("request","state",null,"access_denied");
        assertThat(response.result().name()).isEqualTo(result);
        assertThat(response.consumed()).isEqualTo("null".equals(consumed)?null:Boolean.valueOf(consumed));
        assertThat(response.tokens()).isNull();assertThat(response.toString()).doesNotContain("private detail");server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"{\"login_request_consumed\":true}",
        "{\"login_request_consumed\":true,\"access_token\":42}",
        "{\"login_request_consumed\":true,\"access_expires_at\":\"not a date\"}"})
    void malformedTokenResultStillPreservesConfirmedConsumption(String body) {
        server.expect(requestTo("http://auth.test/auth/oauth/google/callback")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        var response=service.callback("request","state","code",null);
        assertThat(response.result()).isEqualTo(LoginService.Result.FAILED);assertThat(response.consumed()).isTrue();server.verify();
    }
    @Test void missingCallbackInputsNeverCallAuth() {
        assertThat(service.callback(null,"state","code",null).result()).isEqualTo(LoginService.Result.INVALID);
        assertThat(service.callback("request",null,"code",null).result()).isEqualTo(LoginService.Result.INVALID);
        assertThat(service.callback("request","state","code","error").result()).isEqualTo(LoginService.Result.INVALID);
        server.verify();
    }
    @Test void responseLossIsUnavailableWithUnknownConsumptionAndNoRetry() {
        server.expect(requestTo("http://auth.test/auth/oauth/google/callback")).andRespond(withException(new java.net.SocketTimeoutException("private detail")));
        var response=service.callback("request","state","code",null);
        assertThat(response.result()).isEqualTo(LoginService.Result.UNAVAILABLE);assertThat(response.consumed()).isNull();server.verify();
    }
    @Test void timeoutWhileReadingBodyRemainsCommunicationFailure() {
        server.expect(requestTo("http://auth.test/auth/oauth/google/callback")).andRespond(request->{
            var stream=new java.io.InputStream(){public int read() throws java.io.IOException {throw new java.net.SocketTimeoutException("private detail");}};
            var response=new org.springframework.mock.http.client.MockClientHttpResponse(stream,HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);return response;
        });
        var response=service.callback("request","state","code",null);
        assertThat(response.result()).isEqualTo(LoginService.Result.UNAVAILABLE);assertThat(response.consumed()).isNull();server.verify();
    }
}
