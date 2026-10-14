package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.List;
import java.util.UUID;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.client.chat.ChatData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChatApiClientTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID SESSION = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");
    static final UUID FILE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d63");
    static final UUID MEMO = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64");
    static final String SESSION_JSON = "{\"id\":\"" + SESSION + "\",\"project_id\":\"" + PROJECT + "\",\"title\":\"북쪽 문 장면 확인\","
            + "\"updated_at\":\"2026-10-14T03:00:00.000Z\",\"preview\":\"본문이 있는 가장 최근 회차는 12화예요.\"}";
    static final String QUESTION_JSON = "{\"id\":\"" + UUID.randomUUID() + "\",\"session_id\":\"" + SESSION + "\",\"role\":\"user\",\"content\":\"\","
            + "\"intent\":\"explain\",\"quote\":{\"file_id\":\"" + FILE + "\",\"title\":\"12화\",\"doc_type\":\"manuscript\",\"text\":\"어둠 너머\"},"
            + "\"citations\":[],\"created_at\":\"2026-10-14T03:00:00Z\"}";
    static final String ANSWER_JSON = "{\"id\":\"" + UUID.randomUUID() + "\",\"session_id\":\"" + SESSION + "\",\"role\":\"assistant\","
            + "\"content\":\"이 장면은 [[1]]\",\"intent\":null,\"quote\":null,\"citations\":["
            + "{\"kind\":\"document\",\"file_id\":\"" + FILE + "\",\"title\":\"12화\",\"doc_type\":\"manuscript\",\"quote\":null},"
            + "{\"kind\":\"memo\",\"memo_id\":\"" + MEMO + "\",\"title\":\"균열의 방향\",\"quote\":\"균열은 문이 아니라 기억의 방향이다.\"}],"
            + "\"created_at\":\"2026-10-14T03:00:05Z\"}";
    MockRestServiceServer server;
    ChatApiClient client;

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("http://chat.test");
        var mocks = new MockServerRestClientCustomizer();
        mocks.customize(builder);
        server = mocks.getServer();
        client = new ChatApiClient(builder.build(), null);
    }

    @Test void readsAvailabilityAndSessionsWithOnlyTheVerifiedUser() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/availability")).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("Cookie")).andExpect(headerDoesNotExist("X-User-Locale"))
            .andRespond(withSuccess("{\"status\":\"too-large\",\"size\":1500000,\"limit\":1400000}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("X-User-Display-Name"))
            .andRespond(withSuccess("{\"sessions\":[" + SESSION_JSON + "]}", MediaType.APPLICATION_JSON));
        assertThat(client.availability(USER, PROJECT)).isEqualTo(new ChatData.Availability("too-large", 1500000L, 1400000L));
        assertThat(client.sessions(USER, PROJECT).sessions()).singleElement().satisfies(session -> {
            assertThat(session.id()).isEqualTo(SESSION);
            assertThat(session.projectId()).isEqualTo(PROJECT);
            assertThat(session.title()).isEqualTo("북쪽 문 장면 확인");
        });
        server.verify();
    }

    @Test void createsASessionWithoutBodyAndWithPercentEncodedProfile() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(header("X-User-Locale", "en"))
            .andExpect(header("X-User-Display-Name", "%EC%9C%A0%EB%A6%AC%20%EC%9E%91%EA%B0%80%20%26%20Co%2B%2F%25"))
            .andExpect(content().string(""))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(SESSION_JSON));
        assertThat(client.createSession(USER, PROJECT, new ChatData.Profile("en", "유리 작가 & Co+/%")).id()).isEqualTo(SESSION);
        server.verify();
    }

    @ParameterizedTest @CsvSource(nullValues = "NULL", value = {"NULL", "fr", "KO"})
    void omitsTheLocaleHeaderUnlessItIsSupported(String locale) {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andExpect(method(HttpMethod.POST))
            .andExpect(headerDoesNotExist("X-User-Locale")).andExpect(header("X-User-Display-Name", "Writer"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(SESSION_JSON));
        client.createSession(USER, PROJECT, new ChatData.Profile(locale, "Writer"));
        server.verify();
    }

    @Test void searchEncodesTheQueryExactlyOnce() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/search?q=%ED%95%AD%ED%95%B4%20%26%20%2B%3F"))
            .andExpect(method(HttpMethod.GET)).andExpect(header("X-User-Id", USER.toString()))
            .andRespond(withSuccess("{\"hits\":[{\"session\":" + SESSION_JSON + ",\"snippet\":{\"before\":\"긴 \",\"match\":\"항해\",\"after\":\" 끝에\"}},"
                + "{\"session\":" + SESSION_JSON + ",\"snippet\":null}]}", MediaType.APPLICATION_JSON));
        var hits = client.search(USER, PROJECT, "항해 & +?").hits();
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).snippet()).isEqualTo(new ChatData.Snippet("긴 ", "항해", " 끝에"));
        assertThat(hits.get(1).snippet()).isNull();
        server.verify();
    }

    @Test void renamesWithTheContractBody() {
        server.expect(requestTo("http://chat.test/chat/sessions/" + SESSION)).andExpect(method(HttpMethod.PATCH))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(content().json("{\"title\":\"새 이름\"}", JsonCompareMode.STRICT))
            .andRespond(withSuccess(SESSION_JSON, MediaType.APPLICATION_JSON));
        assertThat(client.renameSession(USER, SESSION, new ChatData.Rename("새 이름")).id()).isEqualTo(SESSION);
        server.verify();
    }

    @Test void readsMessagesWithBothCitationKinds() {
        server.expect(requestTo("http://chat.test/chat/sessions/" + SESSION + "/messages")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"messages\":[" + QUESTION_JSON + "," + ANSWER_JSON + "],\"generating\":true}", MediaType.APPLICATION_JSON));
        var messages = client.messages(USER, SESSION);
        assertThat(messages.generating()).isTrue();
        assertThat(messages.messages().get(0).quote()).isEqualTo(new ChatData.Quote(FILE, "12화", "manuscript", "어둠 너머"));
        assertThat(messages.messages().get(1).citations()).containsExactly(
            new ChatData.DocumentCitation(FILE, "12화", "manuscript", null),
            new ChatData.MemoCitation(MEMO, "균열의 방향", "균열은 문이 아니라 기억의 방향이다."));
        server.verify();
    }

    @Test void deletesCancelsAndCleansUpWithNoContent() {
        server.expect(requestTo("http://chat.test/chat/sessions/" + SESSION)).andExpect(method(HttpMethod.DELETE))
            .andExpect(header("X-User-Id", USER.toString())).andRespond(withNoContent());
        server.expect(requestTo("http://chat.test/chat/sessions/" + SESSION + "/cancel")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(content().string("")).andRespond(withNoContent());
        server.expect(requestTo("http://chat.test/users/me/data")).andExpect(method(HttpMethod.DELETE))
            .andExpect(header("X-User-Id", USER.toString())).andRespond(withNoContent());
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/data")).andExpect(method(HttpMethod.DELETE))
            .andExpect(header("X-User-Id", USER.toString())).andRespond(withNoContent());
        client.deleteSession(USER, SESSION);
        client.cancel(USER, SESSION);
        client.deleteUserData(USER);
        client.deleteProjectData(USER, PROJECT);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"404,CHAT_SESSION_NOT_FOUND,404,CHAT_SESSION_NOT_FOUND,NONE", "404,PROJECT_NOT_FOUND,404,PROJECT_NOT_FOUND,NONE",
        "400,CHAT_TITLE_REQUIRED,400,CHAT_TITLE_REQUIRED,NONE", "400,CHAT_QUESTION_REQUIRED,400,CHAT_QUESTION_REQUIRED,NONE",
        "400,INVALID_REQUEST,400,INVALID_REQUEST,NONE", "409,CHAT_NOT_LAST_QUESTION,409,CHAT_NOT_LAST_QUESTION,NONE",
        "409,CHAT_ANSWER_IN_PROGRESS,409,CHAT_ANSWER_IN_PROGRESS,NONE", "422,CHAT_CONTEXT_TOO_LARGE,422,CHAT_CONTEXT_TOO_LARGE,NONE",
        "503,CHAT_MODEL_UNAVAILABLE,503,CHAT_MODEL_UNAVAILABLE,RETRY_LATER", "503,CONTENT_UNAVAILABLE,503,CONTENT_UNAVAILABLE,RETRY_LATER",
        "500,INTERNAL_ERROR,502,UPSTREAM_INVALID_RESPONSE,NONE", "403,CHAT_NOT_ENABLED,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "400,CHAT_SESSION_NOT_FOUND,502,UPSTREAM_INVALID_RESPONSE,NONE", "503,AI_CHAT_UNAVAILABLE,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "401,USER_CONTEXT_REQUIRED,502,UPSTREAM_INVALID_RESPONSE,NONE"})
    void passesThroughOnlyContractedErrors(int status, String code, int external, String result, String action) {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andRespond(withStatus(HttpStatusCode.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"" + code + "\",\"message\":\"private detail\",\"next_action\":\"NONE\"}"));
        var failure = catchThrowableOfType(ChatCallFailure.class, () -> client.sessions(USER, PROJECT));
        assertThat(failure.status()).isEqualTo(external);
        assertThat(failure.code()).isEqualTo(result);
        assertThat(failure.nextAction()).isEqualTo(action);
        assertThat(failure.getMessage()).doesNotContain("private");
        server.verify();
    }

    @Test void errorWithoutJsonBodyIsInvalid() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>bad gateway</html>"));
        assertThatThrownBy(() -> client.sessions(USER, PROJECT)).isInstanceOf(ChatCallFailure.class).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @Test void lostResponseIsUnavailable() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andRespond(withException(new java.net.SocketTimeoutException("private")));
        var failure = catchThrowableOfType(ChatCallFailure.class, () -> client.sessions(USER, PROJECT));
        assertThat(failure.status()).isEqualTo(503);
        assertThat(failure.code()).isEqualTo("AI_CHAT_UNAVAILABLE");
        assertThat(failure.nextAction()).isEqualTo("RETRY_LATER");
        server.verify();
    }

    @Test void unexpectedSuccessStatusIsInvalid() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(SESSION_JSON, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.createSession(USER, PROJECT, null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"messages\":[],\"generating\":null}", "{\"messages\":null,\"generating\":false}",
        "{\"messages\":[null],\"generating\":false}", "{\"messages\":[],\"generating\":\"yes\"}", "{\"messages\":[],\"generating\":false} {}"})
    void rejectsMalformedMessageLists(String payload) {
        server.expect(requestTo("http://chat.test/chat/sessions/" + SESSION + "/messages")).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.messages(USER, SESSION)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"role\":\"assistant\"|\"role\":\"system\"", "\"intent\":null|\"intent\":\"summarize\"",
        "\"kind\":\"memo\"|\"kind\":\"relation\"", "\"memo_id\":\"" + "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64" + "\",|",
        "\"created_at\":\"2026-10-14T03:00:05Z\"|\"created_at\":null", "\"content\":\"이 장면은 [[1]]\"|\"content\":null",
        "\"citations\":[|\"citations\":null,\"x\":["})
    void rejectsMessagesOutsideTheContract(String replacement) {
        var parts = replacement.split("\\|", -1);
        var answer = ANSWER_JSON.replace(parts[0], parts[1]);
        assertThat(answer).isNotEqualTo(ANSWER_JSON);
        server.expect(requestTo("http://chat.test/chat/sessions/" + SESSION + "/messages"))
            .andRespond(withSuccess("{\"messages\":[" + answer + "],\"generating\":false}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.messages(USER, SESSION)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"maybe\",\"size\":1,\"limit\":2}", "{\"status\":\"available\",\"size\":-1,\"limit\":2}",
        "{\"status\":\"available\",\"size\":1}", "{\"status\":\"available\",\"size\":\"1\",\"limit\":2}"})
    void rejectsMalformedAvailability(String payload) {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/availability")).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.availability(USER, PROJECT)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @Test void ignoresUncontractedFieldsInSessions() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions"))
            .andRespond(withSuccess("{\"sessions\":[" + SESSION_JSON.replace("{\"id\"", "{\"user_id\":\"" + USER + "\",\"id\"") + "]}", MediaType.APPLICATION_JSON));
        assertThat(client.sessions(USER, PROJECT).sessions()).extracting(ChatData.Session::id).isEqualTo(List.of(SESSION));
        server.verify();
    }
}
