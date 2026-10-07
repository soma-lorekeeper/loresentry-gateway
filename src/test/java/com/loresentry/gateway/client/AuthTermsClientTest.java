package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.loresentry.gateway.client.auth.AuthApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthTermsClientTest {
    MockRestServiceServer server;AuthApiClient client;
    static final String ID="A".repeat(43), VERSION="00000000-0000-0000-0000-000000000001";
    @BeforeEach void setup(){
        var builder=RestClient.builder().baseUrl("http://auth.test");var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        server=mocks.getServer();client=new AuthApiClient(builder.build());
    }
    @Test void queryUsesConsentHeaderAndOnlyAcceptPostCarriesInternalCredentialInBody(){
        server.expect(requestTo("http://auth.test/auth/terms")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Consent-Request-Id",ID)).andExpect(headerDoesNotExist("Cookie")).andExpect(headerDoesNotExist("X-User-Id"))
                .andRespond(withSuccess("""
                    {"terms_version_id":"00000000-0000-0000-0000-000000000001","version":"1","title":"Terms","content":"Original","effective_at":"2026-09-30T00:00:00Z","expires_at":"2026-09-30T00:30:00Z"}
                    """,MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://auth.test/auth/terms/accept")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"consent_request_id\":\""+ID+"\",\"terms_version_id\":\""+VERSION+"\"}"))
                .andExpect(headerDoesNotExist("Cookie")).andRespond(withSuccess("{\"session_id\":\""+ID+"\",\"expires_at\":\"2026-10-14T00:00:00Z\"}",MediaType.APPLICATION_JSON));
        var terms=client.terms(ID,null);
        assertThat(terms.termsVersionId()).isEqualTo(VERSION);assertThat(terms.locale()).isNull();
        assertThat(client.acceptTerms(ID,VERSION).sessionId()).isEqualTo(ID);server.verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(nullValues="NULL",value={"en,http://auth.test/auth/terms?locale=en,en","ko,http://auth.test/auth/terms?locale=ko,ko",
        "NULL,http://auth.test/auth/terms,ko","'',http://auth.test/auth/terms,ko","fr,http://auth.test/auth/terms,ko","EN,http://auth.test/auth/terms,ko",
        "ko-KR,http://auth.test/auth/terms,ko","'en&locale=ko',http://auth.test/auth/terms,ko","en%26x,http://auth.test/auth/terms,ko"})
    void forwardsOnlySupportedLocaleAndReturnsTheTextLanguage(String locale,String upstream,String returned){
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.GET)).andExpect(header("X-Consent-Request-Id",ID))
                .andRespond(withSuccess("{\"terms_version_id\":\""+VERSION+"\",\"version\":\"1\",\"title\":\"Terms\",\"content\":\"Text\",\"effective_at\":\"2026-09-30T00:00:00Z\",\"expires_at\":\"2026-09-30T00:30:00Z\",\"locale\":\""+returned+"\"}",MediaType.APPLICATION_JSON));
        assertThat(client.terms(ID,locale).locale()).isEqualTo(returned);server.verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"\"fr\"","\"EN\"","\"\"","42","[\"en\"]"})
    void unsupportedTermsLocaleIsAnInvalidResponse(String locale){
        server.expect(requestTo("http://auth.test/auth/terms?locale=en"))
                .andRespond(withSuccess("{\"terms_version_id\":\""+VERSION+"\",\"version\":\"1\",\"title\":\"Terms\",\"content\":\"Text\",\"effective_at\":\"2026-09-30T00:00:00Z\",\"expires_at\":\"2026-09-30T00:30:00Z\",\"locale\":"+locale+"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.terms(ID,"en")).isInstanceOfSatisfying(com.loresentry.gateway.client.auth.AuthCallFailure.class,
                failure->assertThat(failure.kind()).isEqualTo(com.loresentry.gateway.client.auth.AuthCallFailure.Kind.INVALID_RESPONSE));server.verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"400,INVALID_REQUEST,NONE","401,CONSENT_REQUEST_INVALID,RESTART_LOGIN","409,TERMS_VERSION_MISMATCH,NONE","503,LOGIN_UNAVAILABLE,RESTART_LOGIN"})
    void knownConsentFailuresRetainTheirContract(int status,String code,String action) {
        server.expect(requestTo("http://auth.test/auth/terms/accept"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\""+code+"\",\"message\":\"private detail\",\"next_action\":\""+action+"\"}"));
        assertThatThrownBy(()->client.acceptTerms(ID,VERSION)).isInstanceOfSatisfying(com.loresentry.gateway.client.auth.AuthCallFailure.class, failure->{
            assertThat(failure.kind()).isEqualTo(com.loresentry.gateway.client.auth.AuthCallFailure.Kind.CONTRACT);
            assertThat(failure.status()).isEqualTo(status);assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).doesNotContain("private detail");
        });server.verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{}","{\"session_id\":42,\"expires_at\":\"2026-10-14T00:00:00Z\"}","{\"session_id\":\"invalid\",\"expires_at\":\"2026-10-14T00:00:00Z\"}","[]"})
    void malformedSuccessNeverBecomesAnAuthenticatedSession(String body) {
        server.expect(requestTo("http://auth.test/auth/terms/accept")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.acceptTerms(ID,VERSION)).isInstanceOfSatisfying(com.loresentry.gateway.client.auth.AuthCallFailure.class,
                failure->assertThat(failure.kind()).isEqualTo(com.loresentry.gateway.client.auth.AuthCallFailure.Kind.INVALID_RESPONSE));server.verify();
    }
    @Test void responseLossIsUnavailableAndDoesNotRepeatThePost() {
        server.expect(requestTo("http://auth.test/auth/terms/accept")).andRespond(withException(new java.net.SocketTimeoutException("private")));
        assertThatThrownBy(()->client.acceptTerms(ID,VERSION)).isInstanceOfSatisfying(com.loresentry.gateway.client.auth.AuthCallFailure.class,
                failure->assertThat(failure.kind()).isEqualTo(com.loresentry.gateway.client.auth.AuthCallFailure.Kind.UNAVAILABLE));server.verify();
    }

}
