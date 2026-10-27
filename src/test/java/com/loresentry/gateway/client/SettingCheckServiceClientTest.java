package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.application.ChatAllowlist;
import com.loresentry.gateway.application.ChatNotEnabled;
import com.loresentry.gateway.application.SettingCheckService;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.SettingCheckData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SettingCheckServiceClientTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID RUN = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d70");
    static final UUID CHAPTER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d71");
    static final String RUN_JSON = "{\"id\":\"" + RUN + "\",\"project_id\":\"" + PROJECT + "\",\"document_id\":\"" + CHAPTER + "\","
            + "\"document_title\":\"12화\",\"revision_no\":1,\"status\":\"RUNNING\",\"stage\":\"READING\",\"created_at\":\"2026-10-27T12:40:00Z\","
            + "\"finished_at\":null,\"read\":{\"manuscripts\":0,\"from_ordinal\":null,\"to_ordinal\":null,\"settings\":0,\"core_facts\":0},"
            + "\"counts\":{\"open\":0,\"intended\":0,\"not_conflict\":0},\"failure_code\":null}";
    MockRestServiceServer auth, chat;

    SettingCheckService service(String allowedUsers) {
        var authBuilder = RestClient.builder().baseUrl("http://auth.test");
        var authMocks = new MockServerRestClientCustomizer(); authMocks.customize(authBuilder); auth = authMocks.getServer();
        var chatBuilder = RestClient.builder().baseUrl("http://chat.test");
        var chatMocks = new MockServerRestClientCustomizer(); chatMocks.customize(chatBuilder); chat = chatMocks.getServer();
        return new SettingCheckService(new ChatApiClient(chatBuilder.build(), null), new AccountService(new AuthApiClient(authBuilder.build())),
                ChatAllowlist.parse(allowedUsers));
    }

    void account(String locale) {
        auth.expect(requestTo("http://auth.test/auth/users/me")).andExpect(method(HttpMethod.GET)).andExpect(header("X-User-Id", USER.toString()))
            .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"display_name\":\"유리 작가\",\"email\":\"writer@example.test\","
                + "\"onboarding_completed\":true,\"locale\":" + locale + "}", MediaType.APPLICATION_JSON));
    }

    void started(org.springframework.test.web.client.RequestMatcher... matchers) {
        var expectation = chat.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("X-User-Display-Name"))
            .andExpect(content().json("{\"document_id\":\"" + CHAPTER + "\"}", JsonCompareMode.STRICT));
        for (var matcher : matchers) expectation.andExpect(matcher);
        expectation.andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(RUN_JSON));
    }

    @Test void startCarriesTheAccountLocaleButNotTheName() {
        var service = service(USER.toString());
        account("\"en\"");
        started(header("X-User-Locale", "en"));
        assertThat(service.start(USER, PROJECT, new SettingCheckData.Start(CHAPTER)).id()).isEqualTo(RUN);
        auth.verify(); chat.verify();
    }

    @Test void unknownLocaleIsOmitted() {
        var service = service("*");
        account("null");
        started(headerDoesNotExist("X-User-Locale"));
        service.start(USER, PROJECT, new SettingCheckData.Start(CHAPTER));
        auth.verify(); chat.verify();
    }

    @Test void authFailureStillStartsWithoutTheLocale() {
        var service = service("*");
        auth.expect(requestTo("http://auth.test/auth/users/me")).andRespond(withException(new java.net.SocketTimeoutException("private")));
        started(headerDoesNotExist("X-User-Locale"));
        service.start(USER, PROJECT, new SettingCheckData.Start(CHAPTER));
        auth.verify(); chat.verify();
    }

    @Test void readsDoNotLoadTheAccount() {
        var service = service("*");
        chat.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks/summary")).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("X-User-Locale"))
            .andRespond(withSuccess("{\"running\":null,\"chapters\":[]}", MediaType.APPLICATION_JSON));
        assertThat(service.summary(USER, PROJECT).chapters()).isEmpty();
        auth.verify(); chat.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"", " , ", "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d69"})
    void usersOutsideTheAllowlistReachNeitherAuthNorChat(String allowedUsers) {
        var service = service(allowedUsers);
        assertThatThrownBy(() -> service.start(USER, PROJECT, new SettingCheckData.Start(CHAPTER))).isInstanceOf(ChatNotEnabled.class);
        assertThatThrownBy(() -> service.summary(USER, PROJECT)).isInstanceOf(ChatNotEnabled.class);
        assertThatThrownBy(() -> service.run(USER, RUN)).isInstanceOf(ChatNotEnabled.class);
        assertThatThrownBy(() -> service.clearVerdict(USER, RUN)).isInstanceOf(ChatNotEnabled.class);
        auth.verify(); chat.verify();
    }
}
