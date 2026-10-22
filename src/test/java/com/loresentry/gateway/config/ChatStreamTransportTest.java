package com.loresentry.gateway.config;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.loresentry.gateway.FakeChatUpstream;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.ChatStreamTransport;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class ChatStreamTransportTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID SESSION = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID FILE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");
    static final UUID CLIENT_MESSAGE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d63");
    static final ChatData.SendInput INPUT = new ChatData.SendInput("이 장면은?", "explain",
            new ChatData.Quote(FILE, "12화 · 균열의 밤", "manuscript", "어둠 너머"), FILE, null, CLIENT_MESSAGE);
    static final ChatData.Profile PROFILE = new ChatData.Profile("ko", "유리 작가 & Co+");
    static final String PHASE = "event: phase\ndata: {\"phase\":\"gathering\"}\n\n";
    static final String PING = ": ping\n\n";
    static final String DONE = "event: done\ndata: {\"question\":{},\"answer\":{},\"usage\":{\"used\":4,\"limit\":20,\"resets_at\":\"2026-10-22T15:00:00.000Z\"}}\n\n";

    static final class Browser extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch firstEvent = new CountDownLatch(1);
        private final int failingWrite;
        private int writes;
        Browser() { this(-1); }
        Browser(int failingWrite) { this.failingWrite = failingWrite; }
        @Override public synchronized void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
        @Override public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
            if (++writes == failingWrite) throw new IOException("browser disconnected");
            bytes.write(buffer, offset, length);
        }
        @Override public synchronized void flush() { if (text().contains("\n\n")) firstEvent.countDown(); }
        synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }

    static ChatApiClient client(String baseUrl, CloseableHttpClient http) {
        return new ChatApiClient(null, new ChatStreamTransport(http, baseUrl));
    }

    @Test
    void relaysEachEventAsSoonAsItArrivesWithIdentityAndProfile() throws Exception {
        var browser = new Browser();
        try (var upstream = FakeChatUpstream.start(exchange -> {
                exchange.startEvents();
                exchange.chunk(PHASE);
                assertThat(browser.firstEvent.await(5, TimeUnit.SECONDS)).isTrue();
                exchange.chunk(PING);
                exchange.chunk(DONE);
                exchange.end();
            });
            var http = ChatConfiguration.streamHttpClient()) {
            client(upstream.baseUrl(), http).send(USER, SESSION, INPUT, PROFILE).relay(browser);
            upstream.awaitScript();
            assertThat(browser.text()).isEqualTo(PHASE + PING + DONE);
            var request = upstream.request();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/chat/sessions/" + SESSION + "/messages");
            assertThat(request.header("X-User-Id")).isEqualTo(USER.toString());
            assertThat(request.header("X-User-Locale")).isEqualTo("ko");
            assertThat(request.header("X-User-Display-Name")).isEqualTo("%EC%9C%A0%EB%A6%AC%20%EC%9E%91%EA%B0%80%20%26%20Co%2B");
            assertThat(request.header("Content-Type")).startsWith("application/json");
            assertThat(request.header("Accept")).contains("text/event-stream");
            assertThat(request.header("Cookie")).isNull();
            var json = JsonMapper.builder().build();
            assertThat(json.readTree(request.body())).isEqualTo(json.readTree("{\"content\":\"이 장면은?\",\"intent\":\"explain\","
                    + "\"quote\":{\"file_id\":\"" + FILE + "\",\"title\":\"12화 · 균열의 밤\",\"doc_type\":\"manuscript\",\"text\":\"어둠 너머\"},"
                    + "\"focus_file_id\":\"" + FILE + "\",\"replaces\":null,\"client_message_id\":\"" + CLIENT_MESSAGE + "\"}"));
        }
    }

    @Test
    void sendsNoProfileHeadersWithoutAProfile() throws Exception {
        try (var upstream = FakeChatUpstream.start(exchange -> { exchange.startEvents(); exchange.chunk(DONE); exchange.end(); });
            var http = ChatConfiguration.streamHttpClient()) {
            client(upstream.baseUrl(), http).send(USER, SESSION, INPUT, null).relay(new Browser());
            assertThat(upstream.request().headers()).doesNotContainKeys("x-user-locale", "x-user-display-name");
        }
    }

    @Test
    void everySendOpensItsOwnConnection() throws Exception {
        try (var upstream = FakeChatUpstream.listen(); var http = ChatConfiguration.streamHttpClient()) {
            var client = client(upstream.baseUrl(), http);
            for (int turn = 0; turn < 3; turn++) {
                var call = upstream.expect(exchange -> { exchange.startEvents(); exchange.chunk(DONE); exchange.end(); });
                var browser = new Browser();
                client.send(USER, SESSION, INPUT, PROFILE).relay(browser);
                call.awaitScript();
                assertThat(browser.text()).isEqualTo(DONE);
            }
        }
    }

    @Test
    void survivesSilenceLongerThanTheSharedClientReadTimeout() throws Exception {
        var browser = new Browser();
        try (var upstream = FakeChatUpstream.start(exchange -> {
                exchange.startEvents();
                exchange.chunk(PHASE);
                Thread.sleep(Duration.ofSeconds(11));
                exchange.chunk(PING);
                exchange.chunk(DONE);
                exchange.end();
            });
            var http = ChatConfiguration.streamHttpClient()) {
            client(upstream.baseUrl(), http).send(USER, SESSION, INPUT, PROFILE).relay(browser);
            upstream.awaitScript();
            assertThat(browser.text()).isEqualTo(PHASE + PING + DONE);
        }
    }

    @ParameterizedTest
    @CsvSource({"409,application/json,CHAT_ANSWER_IN_PROGRESS,409,CHAT_ANSWER_IN_PROGRESS,NONE",
        "409,application/json,CHAT_NOT_LAST_QUESTION,409,CHAT_NOT_LAST_QUESTION,NONE",
        "422,application/json,CHAT_CONTEXT_TOO_LARGE,422,CHAT_CONTEXT_TOO_LARGE,NONE",
        "429,application/json,CHAT_DAILY_LIMIT_REACHED,429,CHAT_DAILY_LIMIT_REACHED,NONE",
        "409,application/json,CHAT_DAILY_LIMIT_REACHED,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "404,application/json,CHAT_SESSION_NOT_FOUND,404,CHAT_SESSION_NOT_FOUND,NONE",
        "400,application/json,CHAT_QUESTION_REQUIRED,400,CHAT_QUESTION_REQUIRED,NONE",
        "503,application/json,CHAT_MODEL_UNAVAILABLE,503,CHAT_MODEL_UNAVAILABLE,RETRY_LATER",
        "503,application/json,CONTENT_UNAVAILABLE,503,CONTENT_UNAVAILABLE,RETRY_LATER",
        "500,application/json,INTERNAL_ERROR,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "409,application/json,CHAT_MODEL_UNAVAILABLE,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "500,text/html,CHAT_MODEL_UNAVAILABLE,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "200,application/json,CHAT_ANSWER_IN_PROGRESS,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "201,text/event-stream,CHAT_ANSWER_IN_PROGRESS,502,UPSTREAM_INVALID_RESPONSE,NONE"})
    void answersOtherThanAnEventStreamBecomeChatErrors(int status, String type, String code, int external, String result, String action) throws Exception {
        try (var upstream = FakeChatUpstream.start(exchange -> exchange.respond(status, type,
                "{\"code\":\"" + code + "\",\"message\":\"private detail\",\"next_action\":\"NONE\"}"));
            var http = ChatConfiguration.streamHttpClient()) {
            var failure = catchThrowableOfType(ChatCallFailure.class, () -> client(upstream.baseUrl(), http).send(USER, SESSION, INPUT, PROFILE));
            assertThat(failure.status()).isEqualTo(external);
            assertThat(failure.code()).isEqualTo(result);
            assertThat(failure.nextAction()).isEqualTo(action);
        }
    }

    @Test
    void unreachableUpstreamIsUnavailable() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try (var http = ChatConfiguration.streamHttpClient()) {
            var failure = catchThrowableOfType(ChatCallFailure.class, () -> client("http://127.0.0.1:" + port, http).send(USER, SESSION, INPUT, PROFILE));
            assertThat(failure.status()).isEqualTo(503);
            assertThat(failure.code()).isEqualTo("AI_CHAT_UNAVAILABLE");
            assertThat(failure.nextAction()).isEqualTo("RETRY_LATER");
        }
    }

    @Test
    void browserDisconnectClosesTheUpstreamConnection() throws Exception {
        var detected = new CompletableFuture<Duration>();
        try (var upstream = FakeChatUpstream.start(exchange -> {
                exchange.startEvents();
                exchange.chunk(PHASE);
                long started = System.nanoTime();
                try {
                    while (System.nanoTime() - started < Duration.ofSeconds(20).toNanos()) {
                        exchange.chunk(PING);
                        Thread.sleep(50);
                    }
                } catch (IOException closed) {
                    detected.complete(Duration.ofNanos(System.nanoTime() - started));
                }
            });
            var http = ChatConfiguration.streamHttpClient()) {
            var stream = client(upstream.baseUrl(), http).send(USER, SESSION, INPUT, PROFILE);
            assertThatThrownBy(() -> stream.relay(new Browser(2))).isInstanceOf(IOException.class);
            assertThat(detected.get(10, TimeUnit.SECONDS)).isLessThan(Duration.ofSeconds(5));
            stream.close();
        }
    }

    @Test
    void silentUpstreamEndsTheRelayAndClosesTheConnection() throws Exception {
        var browser = new Browser();
        var closed = new CompletableFuture<Boolean>();
        try (var upstream = FakeChatUpstream.start(exchange -> {
                exchange.startEvents();
                exchange.chunk(PHASE);
                closed.complete(exchange.closedByPeerWithin(Duration.ofSeconds(5)));
            });
            var http = ChatConfiguration.streamHttpClient(Duration.ofSeconds(2), Duration.ofMillis(300), 2)) {
            long started = System.nanoTime();
            client(upstream.baseUrl(), http).send(USER, SESSION, INPUT, PROFILE).relay(browser);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(browser.text()).isEqualTo(PHASE);
            assertThat(closed.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
