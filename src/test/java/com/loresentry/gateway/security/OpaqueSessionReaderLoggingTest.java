package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loresentry.gateway.client.session.OpaqueSessionReader;
import io.lettuce.core.*;
import io.lettuce.core.api.*;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 세션 저장소를 확인하지 못했을 때 원인 줄(단계·예외 종류)이 남는지, 기동 예열이 예외 없이 결과만 남기는지 확인한다.
 * AppLogger가 넘긴 Logback 이벤트를 그대로 받아 message와 key-value(error.type)를 본다.
 */
class OpaqueSessionReaderLoggingTest {
    private static final RedisURI ENDPOINT = RedisURI.create("localhost", 6379);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(OpaqueSessionReader.class);

    @BeforeEach void attach() { events.start(); logger.addAppender(events); }
    @AfterEach void detach() { logger.detachAppender(events); }

    private Optional<ILoggingEvent> line(String message) {
        return events.list.stream().filter(e -> e.getFormattedMessage().equals(message)).findFirst();
    }
    private static Object field(ILoggingEvent event, String name) {
        return event.getKeyValuePairs().stream().filter(p -> p.key.equals(name)).map(p -> p.value).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    @Test void evalTimeoutLeavesTheStageAndCauseButNoSecrets() throws Exception {
        var client = mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> connecting = mock(ConnectionFuture.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisAsyncCommands<String, String> commands = mock(RedisAsyncCommands.class);
        RedisFuture<String> get = mock(RedisFuture.class);
        RedisFuture<List<String>> eval = mock(RedisFuture.class);
        when(client.connectAsync(StringCodec.UTF8, ENDPOINT)).thenReturn(connecting);
        when(connecting.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(connection);
        when(connection.async()).thenReturn(commands);
        when(commands.get(anyString())).thenReturn(get);
        when(get.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenReturn("{\"schema_version\":2,\"user_id\":\"" + UUID.randomUUID() + "\",\"created_at\":1}");
        when(commands.<List<String>>eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class))).thenReturn(eval);
        when(eval.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException("sensitive-response"));

        String session = "A".repeat(43);
        assertThatThrownBy(() -> new OpaqueSessionVerifier(new OpaqueSessionReader(client, ENDPOINT)).verify(session))
                .hasMessage("SESSION_UNAVAILABLE");

        var failed = line("session store check failed during eval").orElseThrow();
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(field(failed, "error.type")).isEqualTo("java.util.concurrent.TimeoutException");
        assertThat(field(failed, "event.duration")).isNotNull();
        // 예외 메시지와 세션 ID는 어느 줄에도 들어가지 않는다
        assertThat(events.list).allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain("sensitive-response").doesNotContain(session);
            assertThat(e.getThrowableProxy()).isNull();
        });
    }

    @SuppressWarnings("unchecked")
    @Test void connectFailureIsNamedByStage() throws Exception {
        var client = mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> connecting = mock(ConnectionFuture.class);
        when(client.connectAsync(StringCodec.UTF8, ENDPOINT)).thenReturn(connecting);
        when(connecting.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new ExecutionException(new RedisConnectionException("refused")));

        assertThatThrownBy(() -> new OpaqueSessionVerifier(new OpaqueSessionReader(client, ENDPOINT)).verify("A".repeat(43)))
                .hasMessage("SESSION_UNAVAILABLE");
        assertThat(field(line("session store check failed during connect").orElseThrow(), "error.type"))
                .isEqualTo("java.util.concurrent.ExecutionException");
    }

    @SuppressWarnings("unchecked")
    @Test void warmUpConnectsReadsOnceAndCloses() throws Exception {
        var client = mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> connecting = mock(ConnectionFuture.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisAsyncCommands<String, String> commands = mock(RedisAsyncCommands.class);
        RedisFuture<String> get = mock(RedisFuture.class);
        when(client.connectAsync(StringCodec.UTF8, ENDPOINT)).thenReturn(connecting);
        when(connecting.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(connection);
        when(connection.async()).thenReturn(commands);
        when(commands.get("auth:session:{login}:by-id:warm-up")).thenReturn(get);
        when(get.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(null);

        new OpaqueSessionReader(client, ENDPOINT).warmUp(Duration.ofSeconds(5));

        assertThat(line("session store connection warmed up")).isPresent();
        verify(commands, times(1)).get("auth:session:{login}:by-id:warm-up");
        verify(connection).closeAsync();
    }

    @SuppressWarnings("unchecked")
    @Test void warmUpFailureDoesNotStopStartUp() throws Exception {
        var client = mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> connecting = mock(ConnectionFuture.class);
        when(client.connectAsync(StringCodec.UTF8, ENDPOINT)).thenReturn(connecting);
        when(connecting.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());

        assertThatCode(() -> new OpaqueSessionReader(client, ENDPOINT).warmUp(Duration.ofMillis(10))).doesNotThrowAnyException();
        var failed = line("session store warm-up failed").orElseThrow();
        assertThat(field(failed, "error.type")).isEqualTo("java.util.concurrent.TimeoutException");
    }
}
