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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class ProjectDeletionClientTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    MockRestServiceServer content, chat;
    ContentService service;

    @BeforeEach void setup() {
        var contentBuilder = RestClient.builder().baseUrl("http://content.test");
        var contentMocks = new MockServerRestClientCustomizer(); contentMocks.customize(contentBuilder); content = contentMocks.getServer();
        var chatBuilder = RestClient.builder().baseUrl("http://chat.test");
        var chatMocks = new MockServerRestClientCustomizer(); chatMocks.customize(chatBuilder); chat = chatMocks.getServer();
        service = new ContentService(new ContentApiClient(contentBuilder.build()), new ChatApiClient(chatBuilder.build(), null));
    }

    void chatCleanup(ResponseCreator response) {
        chat.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/data")).andExpect(method(HttpMethod.DELETE))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("Cookie")).andRespond(response);
    }

    @Test void removesChatDataAfterContentDeletesTheProject() {
        var order = new ArrayList<String>();
        content.expect(requestTo("http://content.test/projects/" + PROJECT)).andExpect(method(HttpMethod.DELETE))
            .andRespond(request -> { order.add("content"); return withNoContent().createResponse(request); });
        chatCleanup(request -> { order.add("chat"); return withNoContent().createResponse(request); });
        service.deleteProject(USER, PROJECT, new Conditions(null, null, null));
        assertThat(order).containsExactly("content", "chat");
        content.verify(); chat.verify();
    }

    @ParameterizedTest
    @CsvSource({"503,CHAT_MODEL_UNAVAILABLE,RETRY_LATER", "500,INTERNAL_ERROR,NONE", "404,PROJECT_NOT_FOUND,NONE"})
    void chatCleanupFailureStillCountsAsDeleted(int status, String code, String action) {
        content.expect(requestTo("http://content.test/projects/" + PROJECT)).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        chatCleanup(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\"" + code + "\",\"message\":\"m\",\"next_action\":\"" + action + "\"}"));
        assertThatNoException().isThrownBy(() -> service.deleteProject(USER, PROJECT, new Conditions(null, null, null)));
        content.verify(); chat.verify();
    }

    @Test void lostChatCleanupStillCountsAsDeleted() {
        content.expect(requestTo("http://content.test/projects/" + PROJECT)).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        chatCleanup(withException(new java.net.SocketTimeoutException("private")));
        assertThatNoException().isThrownBy(() -> service.deleteProject(USER, PROJECT, new Conditions(null, null, null)));
        content.verify(); chat.verify();
    }

    @Test void contentFailureKeepsChatData() {
        content.expect(requestTo("http://content.test/projects/" + PROJECT)).andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatusCode.valueOf(409)).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"PROJECT_NOT_TRASHED\",\"message\":\"m\",\"next_action\":\"NONE\"}"));
        chat.expect(never(), requestTo("http://chat.test/projects/" + PROJECT + "/chat/data"));
        assertThatThrownBy(() -> service.deleteProject(USER, PROJECT, new Conditions(null, null, null)))
            .isInstanceOf(ContentCallFailure.class).hasMessage("PROJECT_NOT_TRASHED");
        content.verify(); chat.verify();
    }
}
