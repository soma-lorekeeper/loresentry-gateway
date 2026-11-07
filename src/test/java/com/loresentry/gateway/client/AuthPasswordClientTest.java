package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.loresentry.gateway.client.auth.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthPasswordClientTest {
    MockRestServiceServer server;AuthApiClient client;
    @BeforeEach void setup(){
        var builder=RestClient.builder().baseUrl("http://auth.test");var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        server=mocks.getServer();client=new AuthApiClient(builder.build());
    }
    @Test void signUpPostsSnakeCaseFieldsWithoutCredentialsAndReadsTheSession() {
        server.expect(requestTo("http://auth.test/auth/password/signup")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}"))
            .andExpect(headerDoesNotExist("Cookie")).andExpect(headerDoesNotExist("X-User-Id"))
            .andRespond(withSuccess("{\"status\":\"AUTHENTICATED\",\"session_id\":\""+"A".repeat(43)+"\",\"expires_at\":\"2026-11-21T00:00:00Z\"}",MediaType.APPLICATION_JSON));
        var result=client.signUp(new AuthData.SignUp("judge1","correct-horse-7"));
        assertThat(result.status()).isEqualTo("AUTHENTICATED");assertThat(result.sessionId()).isEqualTo("A".repeat(43));
        assertThat(result.toString()).doesNotContain("A".repeat(43));server.verify();
    }
    @Test void loginReadsTermsRequiredAndKnownErrors() {
        server.expect(requestTo("http://auth.test/auth/password/login")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"status\":\"TERMS_REQUIRED\",\"consent_request_id\":\""+"B".repeat(42)+"A\",\"expires_at\":\"2026-11-07T00:30:00Z\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://auth.test/auth/password/login"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"private\",\"next_action\":\"NONE\"}"));
        server.expect(requestTo("http://auth.test/auth/password/login"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"x\",\"next_action\":\"NONE\"}"));
        var result=client.passwordLogin(new AuthData.PasswordLogin("judge1","correct-horse-7"));
        assertThat(result.status()).isEqualTo("TERMS_REQUIRED");assertThat(result.consentRequestId()).isEqualTo("B".repeat(42)+"A");
        assertThatThrownBy(()->client.passwordLogin(new AuthData.PasswordLogin("judge1","x")))
            .isInstanceOfSatisfying(AuthCallFailure.class,failure->{assertThat(failure.kind()).isEqualTo(AuthCallFailure.Kind.CONTRACT);assertThat(failure.code()).isEqualTo("INVALID_CREDENTIALS");assertThat(failure.status()).isEqualTo(401);});
        assertThatThrownBy(()->client.passwordLogin(new AuthData.PasswordLogin("judge1","x")))
            .isInstanceOfSatisfying(AuthCallFailure.class,failure->assertThat(failure.kind()).isEqualTo(AuthCallFailure.Kind.INVALID_RESPONSE));
        server.verify();
    }
}
