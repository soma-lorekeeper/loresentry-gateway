package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.loresentry.gateway.application.*;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.ChatStream;
import com.loresentry.gateway.client.session.OpaqueSessionReader.VerifiedSession;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.security.*;
import com.loresentry.gateway.web.auth.*;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.cors.CorsConfiguration;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(com.loresentry.gateway.web.chat.ChatController.class)
@Import({JsonConfiguration.class, CurrentUserArgumentResolver.class, ChatService.class, ChatRoutesTest.Wiring.class})
class ChatRoutesTest {
    static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    static final String WRITER_SESSION = "A".repeat(43), OUTSIDER_SESSION = "B".repeat(42) + "A";
    static final UUID WRITER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID OUTSIDER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d69");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID CHAT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");
    static final UUID FILE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d63");
    static final UUID MEMO = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64");
    static final UUID MESSAGE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d65");
    static final UUID CLIENT_MESSAGE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d66");
    static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-14T03:00:00Z");
    static final ChatData.Session SESSION = new ChatData.Session(CHAT, PROJECT, "북쪽 문 장면 확인", AT, "본문이 있는 가장 최근 회차는 12화예요.");
    static final String SEND_BODY = "{\"content\":\"이 장면은?\",\"intent\":\"explain\",\"quote\":{\"file_id\":\"" + FILE + "\",\"title\":\"12화\","
            + "\"doc_type\":\"manuscript\",\"text\":\"어둠 너머\"},\"focus_file_id\":\"" + FILE + "\",\"replaces\":null,\"client_message_id\":\"" + CLIENT_MESSAGE + "\"}";
    static final ChatData.SendInput SEND_INPUT = new ChatData.SendInput("이 장면은?", "explain",
            new ChatData.Quote(FILE, "12화", "manuscript", "어둠 너머"), FILE, null, CLIENT_MESSAGE);
    static final String EVENTS = "event: phase\ndata: {\"phase\":\"gathering\"}\n\n: ping\n\nevent: done\ndata: {\"usage\":{\"used\":1,\"limit\":20,\"resets_at\":\"2026-10-22T15:00:00.000Z\"}}\n\n";

    @Autowired MockMvc mvc;
    @MockitoBean OpaqueSessionVerifier verifier;
    @MockitoBean ChatApiClient client;
    @MockitoBean AccountService accounts;

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean CookieSettings names() { return new CookieSettings("__Host-ls_oauth", true); }
        @Bean AuthCookies cookies(CookieSettings names, Clock clock) { return new AuthCookies(names, clock); }
        @Bean @Order(1) CsrfFilter csrf() { var cors = new CorsConfiguration(); cors.setAllowedOrigins(List.of("https://loresentry.com")); return new CsrfFilter(cors); }
        @Bean @Order(2) SessionFilter session(OpaqueSessionVerifier verifier, CookieSettings names, AuthCookies cookies) { return new SessionFilter(verifier, names, cookies); }
        @Bean ChatAllowlist chatAllowlist() { return ChatAllowlist.parse(WRITER.toString()); }
    }

    static final class FakeStream implements ChatStream {
        volatile boolean closed;
        @Override public void relay(OutputStream browser) throws IOException {
            browser.write(EVENTS.getBytes(StandardCharsets.UTF_8));
            browser.flush();
        }
        @Override public void close() { closed = true; }
    }

    @BeforeEach void sessions() {
        when(verifier.verify(WRITER_SESSION)).thenReturn(new VerifiedSession(WRITER, NOW.plusSeconds(1209600)));
        when(verifier.verify(OUTSIDER_SESSION)).thenReturn(new VerifiedSession(OUTSIDER, NOW.plusSeconds(1209600)));
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
    }

    static MockHttpServletRequestBuilder as(String session, MockHttpServletRequestBuilder request) {
        return request.header("Origin", "https://loresentry.com").header("X-LS-CSRF", "1").cookie(new Cookie("__Host-ls_session", session));
    }

    static MockHttpServletRequestBuilder route(String route) {
        var parts = route.split(" ");
        var uri = parts[1].replace("{p}", PROJECT.toString()).replace("{s}", CHAT.toString());
        var request = request(HttpMethod.valueOf(parts[0]), uri);
        if (uri.endsWith("/messages") && parts[0].equals("POST")) request.contentType(MediaType.APPLICATION_JSON).content(SEND_BODY);
        if (parts[0].equals("PATCH")) request.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"새 이름\"}");
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /projects/{p}/chat/availability", "GET /projects/{p}/chat/sessions", "POST /projects/{p}/chat/sessions",
        "GET /projects/{p}/chat/search?q=x", "PATCH /chat/sessions/{s}", "DELETE /chat/sessions/{s}", "GET /chat/sessions/{s}/messages",
        "POST /chat/sessions/{s}/messages", "POST /chat/sessions/{s}/cancel", "GET /chat/usage"})
    void everyRouteTurnsAwayUsersOutsideTheAllowlist(String route) throws Exception {
        mvc.perform(as(OUTSIDER_SESSION, route(route)).accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("CHAT_NOT_ENABLED")).andExpect(jsonPath("$.next_action").value("NONE"))
            .andExpect(jsonPath("$.message").isString()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Set-Cookie"));
        verifyNoInteractions(client, accounts);
    }

    @Test void availabilityAndSessionsUseTheBrowserShape() throws Exception {
        when(client.availability(WRITER, PROJECT)).thenReturn(new ChatData.Availability("available", 128734L, 1400000L));
        when(client.sessions(WRITER, PROJECT)).thenReturn(new ChatData.Sessions(List.of(SESSION)));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/chat/availability"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"status\":\"available\",\"size\":128734,\"limit\":1400000}", JsonCompareMode.STRICT));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/chat/sessions"))).andExpect(status().isOk())
            .andExpect(content().json("{\"sessions\":[{\"id\":\"" + CHAT + "\",\"project_id\":\"" + PROJECT + "\",\"title\":\"북쪽 문 장면 확인\","
                + "\"updated_at\":\"2026-10-14T03:00:00Z\",\"preview\":\"본문이 있는 가장 최근 회차는 12화예요.\"}]}", JsonCompareMode.STRICT));
        verifyNoInteractions(accounts);
    }

    @Test void usageUsesTheBrowserShape() throws Exception {
        when(client.usage(WRITER)).thenReturn(new ChatData.Usage(3, 0, OffsetDateTime.parse("2026-10-22T15:00:00Z")));
        mvc.perform(as(WRITER_SESSION, get("/chat/usage"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"used\":3,\"limit\":0,\"resets_at\":\"2026-10-22T15:00:00Z\"}", JsonCompareMode.STRICT));
        verify(client).usage(WRITER);
        verifyNoInteractions(accounts);
    }

    @Test void usageFailuresUseTheCommonErrorBody() throws Exception {
        when(client.usage(WRITER)).thenThrow(ChatCallFailure.invalid());
        mvc.perform(as(WRITER_SESSION, get("/chat/usage"))).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE")).andExpect(jsonPath("$.next_action").value("NONE"))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void sessionCreationIsCreatedWithTheProfile() throws Exception {
        when(accounts.get(WRITER)).thenReturn(new AccountService.Account(WRITER, "유리 작가", "writer@example.test", true, "ko"));
        when(client.createSession(WRITER, PROJECT, new ChatData.Profile("ko", "유리 작가"))).thenReturn(SESSION);
        mvc.perform(as(WRITER_SESSION, post("/projects/" + PROJECT + "/chat/sessions"))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(CHAT.toString())).andExpect(jsonPath("$.project_id").value(PROJECT.toString()));
        verify(client).createSession(WRITER, PROJECT, new ChatData.Profile("ko", "유리 작가"));
    }

    @Test void searchKeepsTheQueryAndNullSnippets() throws Exception {
        when(client.search(WRITER, PROJECT, "항해 & +")).thenReturn(new ChatData.SearchHits(List.of(
            new ChatData.SearchHit(SESSION, new ChatData.Snippet("긴 ", "항해", " 끝에")), new ChatData.SearchHit(SESSION, null))));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/chat/search").param("q", "항해 & +"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.hits[0].snippet.match").value("항해")).andExpect(jsonPath("$.hits[0].session.id").value(CHAT.toString()))
            .andExpect(jsonPath("$.hits[1].snippet").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void renameDeleteAndCancelReachTheirOwnCalls() throws Exception {
        when(client.renameSession(WRITER, CHAT, new ChatData.Rename("새 이름"))).thenReturn(SESSION);
        mvc.perform(as(WRITER_SESSION, patch("/chat/sessions/" + CHAT)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"새 이름\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("북쪽 문 장면 확인"));
        mvc.perform(as(WRITER_SESSION, delete("/chat/sessions/" + CHAT))).andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(as(WRITER_SESSION, post("/chat/sessions/" + CHAT + "/cancel"))).andExpect(status().isNoContent());
        verify(client).deleteSession(WRITER, CHAT);
        verify(client).cancel(WRITER, CHAT);
        verifyNoInteractions(accounts);
    }

    @Test void messagesKeepTheCitationShapes() throws Exception {
        var question = new ChatData.Message(MESSAGE, CHAT, "user", "", "explain", new ChatData.Quote(FILE, "12화", "manuscript", "어둠 너머"), List.of(), AT);
        var answer = new ChatData.Message(MESSAGE, CHAT, "assistant", "이 장면은 [[1]]", null, null, List.of(
            new ChatData.DocumentCitation(FILE, "12화", "manuscript", null), new ChatData.MemoCitation(MEMO, "균열의 방향", "기억의 방향")), AT);
        when(client.messages(WRITER, CHAT)).thenReturn(new ChatData.Messages(List.of(question, answer), true));
        mvc.perform(as(WRITER_SESSION, get("/chat/sessions/" + CHAT + "/messages"))).andExpect(status().isOk())
            .andExpect(content().json("{\"generating\":true,\"messages\":["
                + "{\"id\":\"" + MESSAGE + "\",\"session_id\":\"" + CHAT + "\",\"role\":\"user\",\"content\":\"\",\"intent\":\"explain\","
                + "\"quote\":{\"file_id\":\"" + FILE + "\",\"title\":\"12화\",\"doc_type\":\"manuscript\",\"text\":\"어둠 너머\"},"
                + "\"citations\":[],\"created_at\":\"2026-10-14T03:00:00Z\"},"
                + "{\"id\":\"" + MESSAGE + "\",\"session_id\":\"" + CHAT + "\",\"role\":\"assistant\",\"content\":\"이 장면은 [[1]]\",\"intent\":null,"
                + "\"quote\":null,\"citations\":[{\"kind\":\"document\",\"file_id\":\"" + FILE + "\",\"title\":\"12화\",\"doc_type\":\"manuscript\",\"quote\":null},"
                + "{\"kind\":\"memo\",\"memo_id\":\"" + MEMO + "\",\"title\":\"균열의 방향\",\"quote\":\"기억의 방향\"}],\"created_at\":\"2026-10-14T03:00:00Z\"}]}",
                JsonCompareMode.STRICT));
    }

    @Test void sendStreamsTheAnswerWithEventStreamHeadersAndOneRenewedCookie() throws Exception {
        when(accounts.get(WRITER)).thenReturn(new AccountService.Account(WRITER, "유리 작가", "writer@example.test", true, "en"));
        var stream = new FakeStream();
        when(client.send(WRITER, CHAT, SEND_INPUT, new ChatData.Profile("en", "유리 작가"))).thenReturn(stream);
        var started = mvc.perform(as(WRITER_SESSION, post("/chat/sessions/" + CHAT + "/messages")).accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON).content(SEND_BODY))
            .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Accel-Buffering", "no"))
            .andReturn().getResponse();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(EVENTS);
        assertThat(response.getContentType()).containsIgnoringCase("charset=utf-8");
        assertThat(response.getHeaders("Set-Cookie")).singleElement().satisfies(cookie ->
            assertThat(cookie).startsWith("__Host-ls_session=" + WRITER_SESSION).contains("Max-Age=1209600"));
        verify(client).send(WRITER, CHAT, SEND_INPUT, new ChatData.Profile("en", "유리 작가"));
    }

    @Test void sendWithoutProfileWhenAuthFails() throws Exception {
        when(accounts.get(WRITER)).thenThrow(new AuthOperationFailure(503, "ACCOUNT_UNAVAILABLE", "RETRY_LATER"));
        when(client.send(WRITER, CHAT, SEND_INPUT, null)).thenReturn(new FakeStream());
        var started = mvc.perform(as(WRITER_SESSION, post("/chat/sessions/" + CHAT + "/messages"))
                .contentType(MediaType.APPLICATION_JSON).content(SEND_BODY)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        verify(client).send(WRITER, CHAT, SEND_INPUT, null);
    }

    @Test void sendFailureIsJsonEvenForEventStreamClients() throws Exception {
        when(client.send(eq(WRITER), eq(CHAT), any(), any())).thenThrow(new ChatCallFailure(409, "CHAT_ANSWER_IN_PROGRESS", "NONE"));
        mvc.perform(as(WRITER_SESSION, post("/chat/sessions/" + CHAT + "/messages")).accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON).content(SEND_BODY))
            .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("CHAT_ANSWER_IN_PROGRESS")).andExpect(jsonPath("$.next_action").value("NONE"))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("X-Accel-Buffering"));
    }

    @Test void dailyLimitOnSendIsTooManyRequestsJson() throws Exception {
        when(client.send(eq(WRITER), eq(CHAT), any(), any())).thenThrow(new ChatCallFailure(429, "CHAT_DAILY_LIMIT_REACHED", "NONE"));
        mvc.perform(as(WRITER_SESSION, post("/chat/sessions/" + CHAT + "/messages")).accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON).content(SEND_BODY))
            .andExpect(status().isTooManyRequests()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("CHAT_DAILY_LIMIT_REACHED")).andExpect(jsonPath("$.next_action").value("NONE"))
            .andExpect(jsonPath("$.message").value("Today's chat usage is used up.")).andExpect(jsonPath("$.length()").value(3))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("X-Accel-Buffering"));
    }

    @ParameterizedTest
    @CsvSource({"503,AI_CHAT_UNAVAILABLE,RETRY_LATER", "502,UPSTREAM_INVALID_RESPONSE,NONE", "503,CHAT_MODEL_UNAVAILABLE,RETRY_LATER", "429,CHAT_DAILY_LIMIT_REACHED,NONE",
        "422,CHAT_CONTEXT_TOO_LARGE,NONE", "404,PROJECT_NOT_FOUND,NONE", "400,CHAT_TITLE_REQUIRED,NONE"})
    void chatFailuresUseTheCommonErrorBody(int status, String code, String action) throws Exception {
        when(client.sessions(WRITER, PROJECT)).thenThrow(new ChatCallFailure(status, code, action));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/chat/sessions")))
            .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.next_action").value(action))
            .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test void malformedIdsAreNotFound() throws Exception {
        mvc.perform(as(WRITER_SESSION, get("/projects/not-a-uuid/chat/sessions")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mvc.perform(as(WRITER_SESSION, get("/chat/sessions/not-a-uuid/messages")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"content\":1,\"intent\":\"ask\"}", "{\"content\":\"x\",\"user_id\":\"x\"}",
        "{\"content\":\"x\",\"intent\":\"ask\",\"client_message_id\":\"not-a-uuid\"}", "{\"content\":\"x\",\"quote\":{\"file_id\":\"x\"}}",
        "{\"content\":\"x\",\"quote\":{\"text\":\"t\",\"page\":1}}", "{\"content\":\"x\"} {}"})
    void malformedSendBodiesAreRejected(String body) throws Exception {
        mvc.perform(as(WRITER_SESSION, post("/chat/sessions/" + CHAT + "/messages")).accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /projects/{p}/chat/sessions", "PATCH /chat/sessions/{s}", "DELETE /chat/sessions/{s}",
        "POST /chat/sessions/{s}/messages", "POST /chat/sessions/{s}/cancel"})
    void writesRequireCsrf(String route) throws Exception {
        mvc.perform(route(route).cookie(new Cookie("__Host-ls_session", WRITER_SESSION)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        verifyNoInteractions(client, accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/projects/{p}/chat/availability", "/chat/usage"})
    void readsRequireASession(String path) throws Exception {
        mvc.perform(get(path.replace("{p}", PROJECT.toString())))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        verifyNoInteractions(client, accounts);
    }
}
