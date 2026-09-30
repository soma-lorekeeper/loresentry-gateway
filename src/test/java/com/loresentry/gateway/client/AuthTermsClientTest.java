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
        assertThat(client.terms(ID).termsVersionId()).isEqualTo(VERSION);
        assertThat(client.acceptTerms(ID,VERSION).sessionId()).isEqualTo(ID);server.verify();
    }
}
