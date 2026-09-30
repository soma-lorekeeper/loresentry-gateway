package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import com.loresentry.gateway.application.AccountDeletionService;
import com.loresentry.gateway.application.AuthOperationFailure;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.content.ContentApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AccountDeletionClientTest {
    MockRestServiceServer auth,content;AccountDeletionService service;UUID user=UUID.randomUUID();
    @BeforeEach void setup() {
        var authBuilder=RestClient.builder().baseUrl("http://auth.test");var authMocks=new MockServerRestClientCustomizer();authMocks.customize(authBuilder);auth=authMocks.getServer();
        var contentBuilder=RestClient.builder().baseUrl("http://content.test");var contentMocks=new MockServerRestClientCustomizer();contentMocks.customize(contentBuilder);content=contentMocks.getServer();
        service=new AccountDeletionService(new AuthApiClient(authBuilder.build()),new ContentApiClient(contentBuilder.build()));
    }
    void account(String email) {
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.GET)).andExpect(header("X-User-Id",user.toString()))
            .andRespond(withSuccess("{\"id\":\""+user+"\",\"display_name\":\"Name\",\"email\":"+email+",\"onboarding_completed\":true}",MediaType.APPLICATION_JSON));
    }
    void purge() {
        content.expect(requestTo("http://content.test/users/me/data")).andExpect(method(HttpMethod.DELETE)).andExpect(header("X-User-Id",user.toString()))
            .andExpect(headerDoesNotExist("Cookie")).andExpect(content().string("")).andRespond(withNoContent());
    }
    static org.springframework.test.web.client.ResponseCreator error(int status,String code,String action) {
        return withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\""+code+"\",\"message\":\"private detail\",\"next_action\":\""+action+"\"}");
    }
    void verifyAll(){auth.verify();content.verify();}
    AuthOperationFailure failure(String confirmation) {
        return catchThrowableOfType(AuthOperationFailure.class,()->service.delete(user,confirmation));
    }

    @Test void confirmsThenPurgesContentThenDeletesTheAccount() {
        account("\"Writer@Example.test\"");purge();
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.DELETE)).andExpect(header("X-User-Id",user.toString()))
            .andExpect(content().string("")).andRespond(withNoContent());
        service.delete(user,"  writer@example.TEST ");verifyAll();
    }
    @ParameterizedTest @ValueSource(strings={"other@example.test","","writer@example.tes"})
    void mismatchDeletesNothing(String confirmation) {
        account("\"writer@example.test\"");content.expect(never(),requestTo("http://content.test/users/me/data"));
        var failure=failure(confirmation);
        assertThat(failure.status()).isEqualTo(400);assertThat(failure.code()).isEqualTo("ACCOUNT_CONFIRMATION_MISMATCH");assertThat(failure.nextAction()).isEqualTo("NONE");
        verifyAll();
    }
    @Test void accountWithoutEmailCannotBeConfirmed() {
        account("null");
        assertThat(failure("").code()).isEqualTo("ACCOUNT_CONFIRMATION_MISMATCH");verifyAll();
    }
    @Test void alreadyDeletedAccountCountsAsDeleted() {
        account("\"writer@example.test\"");purge();
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.DELETE)).andRespond(error(404,"USER_NOT_FOUND","RELOGIN"));
        service.delete(user,"writer@example.test");verifyAll();
    }
    @ParameterizedTest @CsvSource({"500,INTERNAL_ERROR,NONE","400,INVALID_REQUEST,NONE"})
    void contentFailureKeepsTheAccountAndIsRetryable(int status,String code,String action) {
        account("\"writer@example.test\"");
        content.expect(requestTo("http://content.test/users/me/data")).andRespond(error(status,code,action));
        var failure=failure("writer@example.test");
        assertThat(failure.status()).isEqualTo(503);assertThat(failure.code()).isEqualTo("ACCOUNT_DELETION_UNAVAILABLE");assertThat(failure.nextAction()).isEqualTo("RETRY_LATER");
        verifyAll();
    }
    @Test void lostContentResponseIsRetryable() {
        account("\"writer@example.test\"");
        content.expect(requestTo("http://content.test/users/me/data")).andRespond(withException(new java.net.SocketTimeoutException("private")));
        assertThat(failure("writer@example.test").code()).isEqualTo("ACCOUNT_DELETION_UNAVAILABLE");verifyAll();
    }
    @Test void malformedContentResponseIs502() {
        account("\"writer@example.test\"");
        content.expect(requestTo("http://content.test/users/me/data")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        var failure=failure("writer@example.test");
        assertThat(failure.status()).isEqualTo(502);assertThat(failure.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");verifyAll();
    }
    @ParameterizedTest @CsvSource({"503,ACCOUNT_UNAVAILABLE,RETRY_LATER","500,INTERNAL_ERROR,NONE"})
    void authDeleteFailureAfterPurgeIsRetryable(int status,String code,String action) {
        account("\"writer@example.test\"");purge();
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.DELETE)).andRespond(error(status,code,action));
        var failure=failure("writer@example.test");
        assertThat(failure.status()).isEqualTo(503);assertThat(failure.code()).isEqualTo("ACCOUNT_DELETION_UNAVAILABLE");verifyAll();
    }
    @Test void lostAuthDeleteResponseIsRetryable() {
        account("\"writer@example.test\"");purge();
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.DELETE)).andRespond(withException(new java.net.SocketTimeoutException("private")));
        assertThat(failure("writer@example.test").code()).isEqualTo("ACCOUNT_DELETION_UNAVAILABLE");verifyAll();
    }
    @Test void malformedAuthDeleteResponseIs502() {
        account("\"writer@example.test\"");purge();
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThat(failure("writer@example.test").status()).isEqualTo(502);verifyAll();
    }
    @ParameterizedTest @CsvSource({"401,USER_CONTEXT_REQUIRED,RELOGIN,401,USER_CONTEXT_REQUIRED,RELOGIN",
        "404,USER_NOT_FOUND,RELOGIN,404,USER_NOT_FOUND,RELOGIN",
        "503,ACCOUNT_UNAVAILABLE,RETRY_LATER,503,ACCOUNT_DELETION_UNAVAILABLE,RETRY_LATER",
        "500,INTERNAL_ERROR,NONE,503,ACCOUNT_DELETION_UNAVAILABLE,RETRY_LATER",
        "418,TEAPOT,NONE,502,UPSTREAM_INVALID_RESPONSE,NONE"})
    void confirmationLookupFailureDeletesNothing(int status,String code,String action,int external,String result,String next) {
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.GET)).andRespond(error(status,code,action));
        content.expect(never(),requestTo("http://content.test/users/me/data"));
        var failure=failure("writer@example.test");
        assertThat(failure.status()).isEqualTo(external);assertThat(failure.code()).isEqualTo(result);assertThat(failure.nextAction()).isEqualTo(next);
        verifyAll();
    }
    @Test void confirmationForAnotherUserIsRejectedAsInvalid() {
        auth.expect(requestTo("http://auth.test/auth/users/me")).andRespond(withSuccess("{\"id\":\""+UUID.randomUUID()+"\",\"display_name\":\"Other\",\"email\":\"writer@example.test\",\"onboarding_completed\":true}",MediaType.APPLICATION_JSON));
        content.expect(never(),requestTo("http://content.test/users/me/data"));
        assertThat(failure("writer@example.test").code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");verifyAll();
    }
}
