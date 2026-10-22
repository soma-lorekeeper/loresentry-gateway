package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.loresentry.gateway.FakeChatUpstream;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.client.session.OpaqueSessionReader.VerifiedSession;
import com.loresentry.gateway.security.OpaqueSessionVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@com.loresentry.gateway.LocalTestEnvironment
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamingTest {
    static final String SESSION = "A".repeat(43);
    static final UUID WRITER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID CHAT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");
    static final UUID CLIENT_MESSAGE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d66");
    static final String BODY = "{\"content\":\"이 장면은?\",\"intent\":\"ask\",\"quote\":null,\"focus_file_id\":null,\"replaces\":null,"
            + "\"client_message_id\":\"" + CLIENT_MESSAGE + "\"}";
    static final String PHASE = "event: phase\ndata: {\"phase\":\"gathering\"}\n\n";
    static final String PING = ": ping\n\n";
    static final String DONE = "event: done\ndata: {\"question\":{},\"answer\":{}}\n\n";
    static final FakeChatUpstream UPSTREAM;
    static {
        try { UPSTREAM = FakeChatUpstream.listen(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("loresentry.upstream.ai-chat.base-url", UPSTREAM::baseUrl);
        registry.add("loresentry.chat.allowed-users", WRITER::toString);
    }

    @AfterAll
    static void stop() throws IOException { UPSTREAM.close(); }

    @LocalServerPort int port;
    @Autowired Environment environment;
    @MockitoBean OpaqueSessionVerifier verifier;
    @MockitoBean AccountService accounts;

    @BeforeEach void session() {
        when(verifier.verify(SESSION)).thenReturn(new VerifiedSession(WRITER, Instant.now().plus(Duration.ofDays(14))));
        when(accounts.get(WRITER)).thenReturn(new AccountService.Account(WRITER, "유리 작가", "writer@example.test", true, "ko"));
    }

    HttpResponse<InputStream> send() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/sessions/" + CHAT + "/messages"))
            .header("Origin", "http://localhost:3000").header("X-LS-CSRF", "1").header("Cookie", "ls_session=" + SESSION)
            .header("Content-Type", "application/json").header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    static String readUntil(InputStream input, String marker) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) >= 0) {
            bytes.write(next);
            if (bytes.toString(StandardCharsets.UTF_8).endsWith(marker)) break;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void relaysEachEventToTheBrowserWithSseHeadersAndOneRenewedCookie() throws Exception {
        var firstEventSeen = new CountDownLatch(1);
        var call = UPSTREAM.expect(exchange -> {
            exchange.startEvents();
            exchange.chunk(PHASE);
            assertThat(firstEventSeen.await(10, TimeUnit.SECONDS)).isTrue();
            exchange.chunk(PING);
            exchange.chunk(DONE);
            exchange.end();
        });
        var response = send();
        assertThat(response.statusCode()).isEqualTo(200);
        var type = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isTrue();
        assertThat(type.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().allValues("X-Accel-Buffering")).containsExactly("no");
        assertThat(response.headers().allValues("Set-Cookie")).singleElement()
            .satisfies(cookie -> assertThat(cookie).startsWith("ls_session=" + SESSION + ";").contains("Max-Age=").contains("HttpOnly"));
        try (var body = response.body()) {
            var first = CompletableFuture.supplyAsync(() -> {
                try { return readUntil(body, "\n\n"); } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(PHASE);
            firstEventSeen.countDown();
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(PING + DONE);
        }
        call.awaitScript();
        var upstream = call.request();
        assertThat(upstream.method()).isEqualTo("POST");
        assertThat(upstream.path()).isEqualTo("/chat/sessions/" + CHAT + "/messages");
        assertThat(upstream.header("X-User-Id")).isEqualTo(WRITER.toString());
        assertThat(upstream.header("X-User-Locale")).isEqualTo("ko");
        assertThat(upstream.header("X-User-Display-Name")).isEqualTo("%EC%9C%A0%EB%A6%AC%20%EC%9E%91%EA%B0%80");
        assertThat(upstream.headers()).doesNotContainKeys("cookie", "origin", "x-ls-csrf");
        var json = JsonMapper.builder().build();
        assertThat(json.readTree(upstream.body())).isEqualTo(json.readTree(BODY));
    }

    @ParameterizedTest
    @CsvSource({"409,CHAT_ANSWER_IN_PROGRESS", "429,CHAT_DAILY_LIMIT_REACHED"})
    void upstreamRefusalBecomesAJsonErrorWithTheRenewedCookie(int status, String code) throws Exception {
        UPSTREAM.expect(exchange -> exchange.respond(status, "application/json",
            "{\"code\":\"" + code + "\",\"message\":\"private\",\"next_action\":\"NONE\"}"));
        var response = send();
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow()).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.headers().allValues("Set-Cookie")).singleElement().satisfies(cookie -> assertThat(cookie).startsWith("ls_session=" + SESSION + ";"));
        try (var body = response.body()) {
            var error = JsonMapper.builder().build().readTree(body.readAllBytes());
            assertThat(error.get("code").asString()).isEqualTo(code);
            assertThat(error.get("next_action").asString()).isEqualTo("NONE");
            assertThat(error.get("message").asString()).doesNotContain("private");
        }
    }

    @Test
    void browserDisconnectClosesTheUpstreamConnection() throws Exception {
        var detected = new CompletableFuture<Duration>();
        UPSTREAM.expect(exchange -> {
            exchange.startEvents();
            exchange.chunk(PHASE);
            long started = System.nanoTime();
            try {
                while (System.nanoTime() - started < Duration.ofSeconds(30).toNanos()) {
                    exchange.chunk(PING);
                    Thread.sleep(50);
                }
            } catch (IOException closed) {
                detected.complete(Duration.ofNanos(System.nanoTime() - started));
            }
        });
        try (var socket = new Socket("127.0.0.1", port)) {
            var body = BODY.getBytes(StandardCharsets.UTF_8);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST /chat/sessions/" + CHAT + "/messages HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nOrigin: http://localhost:3000\r\nX-LS-CSRF: 1\r\nCookie: ls_session=" + SESSION
                + "\r\nContent-Type: application/json\r\nAccept: text/event-stream\r\nContent-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            assertThat(readUntil(socket.getInputStream(), "gathering")).startsWith("HTTP/1.1 200");
        }
        assertThat(detected.get(15, TimeUnit.SECONDS)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void streamingRelaxesAsyncAndShutdownLimits() {
        assertThat(environment.getProperty("spring.mvc.async.request-timeout", Duration.class)).isEqualTo(Duration.ofSeconds(180));
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class)).isEqualTo(Duration.ofSeconds(150));
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    }
}
