package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.ArrayList;
import java.util.UUID;
import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.client.content.ContentCallFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class SampleHistoryClientTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final String CREATED = "{\"id\":\"" + PROJECT + "\",\"name\":\"별이 없는 세자\",\"description\":\"\",\"last_worked_at\":\"2026-11-12T00:00:00Z\","
            + "\"trashed_at\":null,\"created_at\":\"2026-11-12T00:00:00Z\",\"last_file\":null}";
    MockRestServiceServer content, chat;
    ContentService service;

    @BeforeEach void setup() {
        var contentBuilder = RestClient.builder().baseUrl("http://content.test");
        var contentMocks = new MockServerRestClientCustomizer(); contentMocks.customize(contentBuilder); content = contentMocks.getServer();
        var chatBuilder = RestClient.builder().baseUrl("http://chat.test");
        var chatMocks = new MockServerRestClientCustomizer(); chatMocks.customize(chatBuilder); chat = chatMocks.getServer();
        service = new ContentService(new ContentApiClient(contentBuilder.build()), new ChatApiClient(chatBuilder.build(), null));
    }

    void sample(String url, ResponseCreator response) {
        content.expect(requestTo(url)).andExpect(method(HttpMethod.POST)).andExpect(header("X-User-Id", USER.toString())).andRespond(response);
    }

    void history(ResponseCreator response) {
        chat.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sample-history")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("Cookie")).andRespond(response);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {"NULL,http://content.test/projects/sample", "ko,http://content.test/projects/sample?locale=ko"})
    void seedsTheRecordedAiHistoryAfterContentCreatesTheKoreanSample(String locale, String url) {
        var order = new ArrayList<String>();
        sample(url, request -> { order.add("content"); return withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(CREATED).createResponse(request); });
        history(request -> { order.add("chat"); return withSuccess("{\"seeded\":true}", MediaType.APPLICATION_JSON).createResponse(request); });
        assertThat(service.createSampleProject(USER, locale, new Conditions(null, null, null)).id()).isEqualTo(PROJECT);
        assertThat(order).containsExactly("content", "chat");
        content.verify(); chat.verify();
    }

    @Test void theEnglishSampleHasNoRecordedHistory() {
        sample("http://content.test/projects/sample?locale=en", withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(CREATED));
        chat.expect(never(), requestTo("http://chat.test/projects/" + PROJECT + "/chat/sample-history"));
        assertThat(service.createSampleProject(USER, "en", new Conditions(null, null, null)).id()).isEqualTo(PROJECT);
        content.verify(); chat.verify();
    }

    @ParameterizedTest
    @CsvSource({"503,CHAT_MODEL_UNAVAILABLE,RETRY_LATER", "404,PROJECT_NOT_FOUND,NONE", "500,INTERNAL_ERROR,NONE"})
    void aFailedSeedStillReturnsTheSample(int status, String code, String action) {
        sample("http://content.test/projects/sample", withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(CREATED));
        history(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\"" + code + "\",\"message\":\"m\",\"next_action\":\"" + action + "\"}"));
        assertThat(service.createSampleProject(USER, null, new Conditions(null, null, null)).id()).isEqualTo(PROJECT);
        content.verify(); chat.verify();
    }

    @Test void aLostSeedStillReturnsTheSample() {
        sample("http://content.test/projects/sample", withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(CREATED));
        history(withException(new java.net.SocketTimeoutException("private")));
        assertThat(service.createSampleProject(USER, null, new Conditions(null, null, null)).id()).isEqualTo(PROJECT);
        content.verify(); chat.verify();
    }

    @Test void aFailedSampleSeedsNothing() {
        sample("http://content.test/projects/sample", withStatus(HttpStatusCode.valueOf(500)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\"INTERNAL_ERROR\",\"message\":\"m\",\"next_action\":\"NONE\"}"));
        chat.expect(never(), requestTo("http://chat.test/projects/" + PROJECT + "/chat/sample-history"));
        assertThatThrownBy(() -> service.createSampleProject(USER, null, new Conditions(null, null, null))).isInstanceOf(ContentCallFailure.class);
        content.verify(); chat.verify();
    }
}
