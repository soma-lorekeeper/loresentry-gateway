package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.application.ChatAllowlist;
import com.loresentry.gateway.application.ChatNotEnabled;
import com.loresentry.gateway.application.ChatService;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.chat.ChatApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChatServiceClientTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID SESSION = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");
    static final String SESSION_JSON = "{\"id\":\"" + SESSION + "\",\"project_id\":\"" + PROJECT + "\",\"title\":\"새 대화\","
            + "\"updated_at\":\"2026-10-14T03:00:00Z\",\"preview\":\"\"}";
    MockRestServiceServer auth, chat;

    ChatService service(String allowedUsers) {
        var authBuilder = RestClient.builder().baseUrl("http://auth.test");
        var authMocks = new MockServerRestClientCustomizer(); authMocks.customize(authBuilder); auth = authMocks.getServer();
        var chatBuilder = RestClient.builder().baseUrl("http://chat.test");
        var chatMocks = new MockServerRestClientCustomizer(); chatMocks.customize(chatBuilder); chat = chatMocks.getServer();
        return new ChatService(new ChatApiClient(chatBuilder.build(), null), new AccountService(new AuthApiClient(authBuilder.build())),
                ChatAllowlist.parse(allowedUsers));
    }

    void account(String locale) {
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.GET)).andExpect(header("X-User-Id", USER.toString()))
            .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"display_name\":\"유리 작가\",\"email\":\"writer@example.test\","
                + "\"onboarding_completed\":true,\"locale\":" + locale + "}", MediaType.APPLICATION_JSON));
    }

    void created(org.springframework.test.web.client.RequestMatcher... matchers) {
        var expectation = chat.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", USER.toString()));
        for (var matcher : matchers) expectation.andExpect(matcher);
        expectation.andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(SESSION_JSON));
    }

    @Test void sessionCreationCarriesTheAuthProfile() {
        var service = service(USER.toString());
        account("\"ko\"");
        created(header("X-User-Locale", "ko"), header("X-User-Display-Name", "%EC%9C%A0%EB%A6%AC%20%EC%9E%91%EA%B0%80"));
        assertThat(service.createSession(USER, PROJECT).id()).isEqualTo(SESSION);
        auth.verify(); chat.verify();
    }

    @Test void unknownLocaleIsOmittedButTheNameIsSent() {
        var service = service("*");
        account("null");
        created(headerDoesNotExist("X-User-Locale"), header("X-User-Display-Name", "%EC%9C%A0%EB%A6%AC%20%EC%9E%91%EA%B0%80"));
        service.createSession(USER, PROJECT);
        auth.verify(); chat.verify();
    }

    @ParameterizedTest @ValueSource(ints = {503, 500, 404})
    void authFailureStillCreatesTheSessionWithoutProfile(int status) {
        var service = service("*");
        auth.expect(requestTo("http://auth.test/auth/users/me")).andRespond(withStatus(HttpStatusCode.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"ACCOUNT_UNAVAILABLE\",\"message\":\"m\",\"next_action\":\"RETRY_LATER\"}"));
        created(headerDoesNotExist("X-User-Locale"), headerDoesNotExist("X-User-Display-Name"));
        assertThat(service.createSession(USER, PROJECT).id()).isEqualTo(SESSION);
        auth.verify(); chat.verify();
    }

    @Test void lostAuthResponseStillCreatesTheSessionWithoutProfile() {
        var service = service("*");
        auth.expect(requestTo("http://auth.test/auth/users/me")).andRespond(withException(new java.net.SocketTimeoutException("private")));
        created(headerDoesNotExist("X-User-Locale"), headerDoesNotExist("X-User-Display-Name"));
        service.createSession(USER, PROJECT);
        auth.verify(); chat.verify();
    }

    @Test void readsDoNotLoadTheProfile() {
        var service = service("*");
        chat.expect(requestTo("http://chat.test/projects/" + PROJECT + "/chat/sessions")).andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist("X-User-Locale")).andExpect(headerDoesNotExist("X-User-Display-Name"))
            .andRespond(withSuccess("{\"sessions\":[]}", MediaType.APPLICATION_JSON));
        assertThat(service.sessions(USER, PROJECT).sessions()).isEmpty();
        auth.verify(); chat.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"", " , ", "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d69"})
    void usersOutsideTheAllowlistReachNeitherAuthNorChat(String allowedUsers) {
        var service = service(allowedUsers);
        assertThatThrownBy(() -> service.createSession(USER, PROJECT)).isInstanceOf(ChatNotEnabled.class);
        assertThatThrownBy(() -> service.sessions(USER, PROJECT)).isInstanceOf(ChatNotEnabled.class);
        auth.verify(); chat.verify();
    }
}
