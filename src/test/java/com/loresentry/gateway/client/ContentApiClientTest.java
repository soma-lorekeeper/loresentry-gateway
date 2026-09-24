package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class ContentApiClientTest {
    @Test void encodesSearchExactlyOnceAndOnlyForwardsExplicitIdentity() {
        var builder = RestClient.builder().baseUrl("http://content.test");
        var mocks = new MockServerRestClientCustomizer();mocks.customize(builder);
        var server = mocks.getServer();
        var service = new ContentService(new ContentApiClient(builder.build()));
        var user = UUID.randomUUID();var project = UUID.randomUUID();
        server.expect(requestTo("http://content.test/projects/"+project+"/search?q=%EC%9C%A0%EB%A6%AC%20%26%20%2B%3F"))
            .andExpect(header("X-User-Id",user.toString())).andExpect(headerDoesNotExist("Cookie"))
            .andExpect(headerDoesNotExist("Authorization")).andExpect(header("If-None-Match","\"tag\""))
            .andRespond(withSuccess("{\"hits\":[]}",MediaType.APPLICATION_JSON));
        assertThat(service.search(user,project,"유리 & +?",new Conditions(null,null,"\"tag\"")).hits()).isEmpty();
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{}","null","{\"projects\":[null]}","{\"projects\":[{}]}","{\"projects\":[]} {}","{\"projects\":false}"})
    void rejectsMalformedOrIncompleteSuccess(String payload) {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/projects")).andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->new ContentApiClient(builder.build()).listProjects(UUID.randomUUID(),null))
            .isInstanceOf(com.loresentry.gateway.client.content.ContentCallFailure.class)
            .hasMessage("UPSTREAM_INVALID_RESPONSE");
        mocks.getServer().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"400,INVALID_REQUEST,NONE,INVALID_REQUEST", "409,DOCUMENT_LOCKED,NONE,DOCUMENT_LOCKED",
        "404,FILE_NOT_FOUND,NONE,FILE_NOT_FOUND", "401,USER_CONTEXT_REQUIRED,RELOGIN,UPSTREAM_INVALID_RESPONSE",
        "400,FILE_NOT_FOUND,NONE,UPSTREAM_INVALID_RESPONSE", "409,DOCUMENT_LOCKED,RELOGIN,UPSTREAM_INVALID_RESPONSE",
        "500,SQL_ERROR,NONE,UPSTREAM_INVALID_RESPONSE", "409,DOCUMENT_CONFLICT,NONE,UPSTREAM_INVALID_RESPONSE"})
    void validatesErrorContract(int status,String code,String action,String result) {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/projects")).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\""+code+"\",\"message\":\"private SQL detail\",\"next_action\":\""+action+"\"}"));
        assertThatThrownBy(()->new ContentApiClient(builder.build()).listProjects(UUID.randomUUID(),null))
            .isInstanceOf(com.loresentry.gateway.client.content.ContentCallFailure.class).hasMessage(result);
        mocks.getServer().verify();
    }
    @Test void ignoresUncontractedSensitiveResponseFields() {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/projects"))
            .andRespond(withSuccess("{\"projects\":[],\"refresh_token\":\"secret\"}",MediaType.APPLICATION_JSON));
        var dto=com.loresentry.gateway.web.content.ContentDtos.Projects.from(new ContentApiClient(builder.build()).listProjects(UUID.randomUUID(),null));
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(dto)).isEqualTo("{\"projects\":[]}");
    }
}
