package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.client.auth.AuthApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthAccountClientTest {
    MockRestServiceServer server;AccountService service;UUID user=UUID.randomUUID();
    @BeforeEach void setup(){var builder=RestClient.builder().baseUrl("http://auth.test");var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);server=mocks.getServer();service=new AccountService(new AuthApiClient(builder.build()));}
    @Test void getAndPatchUseOnlyVerifiedUuidAndExplicitPublicFields() {
        server.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.GET)).andExpect(header("X-User-Id",user.toString()))
            .andExpect(headerDoesNotExist("Cookie")).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("{\"id\":\""+user+"\",\"display_name\":\"Name\",\"email\":null,\"private_claim\":\"secret\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.PATCH))
            .andExpect(header("X-User-Id",user.toString())).andExpect(content().json("{\"display_name\":\"New name\"}"))
            .andRespond(withSuccess("{\"id\":\""+user+"\",\"display_name\":\"New name\",\"email\":null}",MediaType.APPLICATION_JSON));
        assertThat(service.get(user)).isEqualTo(new AccountService.Account(user,"Name",null));
        assertThat(service.update(user,"New name").displayName()).isEqualTo("New name");server.verify();
    }
    @ParameterizedTest @CsvSource({"400,INVALID_DISPLAY_NAME,NONE", "401,USER_CONTEXT_REQUIRED,RELOGIN",
        "404,USER_NOT_FOUND,RELOGIN", "503,ACCOUNT_UNAVAILABLE,RETRY_LATER"})
    void knownErrorsKeepTheirOwnCodeAndNeverBecomeAccessRefresh(int status,String code,String action) {
        server.expect(requestTo("http://auth.test/auth/users/me")).andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\""+code+"\",\"message\":\"private detail\",\"next_action\":\""+action+"\"}"));
        assertThatThrownBy(()->service.get(user)).isInstanceOf(com.loresentry.gateway.application.AuthOperationFailure.class).hasMessage(code)
            .satisfies(error->{var failure=(com.loresentry.gateway.application.AuthOperationFailure)error;assertThat(failure.status()).isEqualTo(status);assertThat(failure.nextAction()).isEqualTo(action).isNotEqualTo("REFRESH");});
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"id\":false}","{\"code\":\"UNKNOWN\"}"})
    void invalidResponseIs502(String body) {
        server.expect(requestTo("http://auth.test/auth/users/me")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->service.get(user)).hasMessage("UPSTREAM_INVALID_RESPONSE");server.verify();
    }
    @Test void responseForAnotherUserIsNotExposed() {
        server.expect(requestTo("http://auth.test/auth/users/me")).andRespond(withSuccess("{\"id\":\""+UUID.randomUUID()+"\",\"display_name\":\"Other\",\"email\":null}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->service.get(user)).hasMessage("UPSTREAM_INVALID_RESPONSE");server.verify();
    }
    @Test void lostPatchResponseIsUnavailableAndNotRetried() {
        server.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.PATCH)).andRespond(withException(new java.net.SocketTimeoutException("private")));
        assertThatThrownBy(()->service.update(user,"Name")).hasMessage("ACCOUNT_UNAVAILABLE");server.verify();
    }
}
