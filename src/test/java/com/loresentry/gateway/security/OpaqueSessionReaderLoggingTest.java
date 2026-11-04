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
 * 세션 저장소를 확인하지 못했을 때 저장소 호출 결과 줄(valkey call failed)에 단계·예외 종류가 남는지, 정상적인 거절(세션 없음)은
 * 호출 완료로 남는지, 기동 예열이 붙을 때까지 다시 시도하고 예외 없이 시작·종료 줄만 남기는지 확인한다.
 * AppLogger가 넘긴 Logback 이벤트를 그대로 받아 message와 key-value(error.type, param.stage)를 본다.
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

        var failed = line("valkey call failed").orElseThrow();
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(field(failed, "error.type")).isEqualTo("java.util.concurrent.TimeoutException");
        assertThat(field(failed, "loresentry.param.stage")).isEqualTo("eval");
        assertThat(field(failed, "event.duration")).isInstanceOf(Long.class);
        // 줄 위치는 저장소를 부른 reader 메서드다(repository 레이어)
        assertThat(field(failed, "log.origin.function")).isEqualTo("verifyAndExtend");
        assertThat(field(failed, "log.layer")).isEqualTo("repository");
        // 메시지 없는 스택만 남는다
        assertThat(String.valueOf(field(failed, "error.stack_trace"))).startsWith("java.util.concurrent.TimeoutException\n")
                .doesNotContain("sensitive-response");
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
        var failed = line("valkey call failed").orElseThrow();
        assertThat(field(failed, "error.type")).isEqualTo("java.util.concurrent.ExecutionException");
        assertThat(field(failed, "loresentry.param.stage")).isEqualTo("connect");
    }

    @SuppressWarnings("unchecked")
    @Test void anUnexpectedStoreValueIsAFailedCallWithItsReasonAndAMissingSessionIsACompletedCall() throws Exception {
        var client = mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> connecting = mock(ConnectionFuture.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisAsyncCommands<String, String> commands = mock(RedisAsyncCommands.class);
        RedisFuture<String> get = mock(RedisFuture.class);
        when(client.connectAsync(StringCodec.UTF8, ENDPOINT)).thenReturn(connecting);
        when(connecting.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(connection);
        when(connection.async()).thenReturn(commands);
        when(commands.get(anyString())).thenReturn(get);
        // 저장소의 값이 규칙에 맞지 않는다(예외 없이 분류한 실패)
        when(get.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn("{\"schema_version\":1}");
        var reader = new OpaqueSessionReader(client, ENDPOINT);

        assertThatThrownBy(() -> new OpaqueSessionVerifier(reader).verify("A".repeat(43))).hasMessage("SESSION_UNAVAILABLE");
        var failed = line("valkey call failed").orElseThrow();
        assertThat(field(failed, "error.type")).isEqualTo("UNEXPECTED_STORE_VALUE");
        assertThat(field(failed, "loresentry.param.stage")).isEqualTo("get");
        assertThat(field(failed, "error.stack_trace")).isNull();

        // 세션이 없으면(만료·폐기) 저장소는 정상적으로 답했으므로 호출 완료다. 단계는 실패 줄에만 붙는다
        events.list.clear();
        when(get.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(null);
        assertThatThrownBy(() -> new OpaqueSessionVerifier(reader).verify("A".repeat(43))).hasMessage("SESSION_INVALID");
        var completed = line("valkey call completed").orElseThrow();
        assertThat(completed.getLevel().toString()).isEqualTo("INFO");
        assertThat(field(completed, "loresentry.param.stage")).isNull();
        assertThat(field(completed, "event.duration")).isInstanceOf(Long.class);
        assertThat(line("valkey call failed")).isEmpty();
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

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("session store warm-up start", "session store warm-up completed");
        var completed = line("session store warm-up completed").orElseThrow();
        assertThat(field(completed, "event.duration")).isInstanceOf(Long.class);
        assertThat(field(completed, "loresentry.result.attempts")).isEqualTo(1);
        verify(commands, times(1)).get("auth:session:{login}:by-id:warm-up");
        verify(connection).closeAsync();
    }

    @SuppressWarnings("unchecked")
    @Test void warmUpRetriesUntilTheStoreAnswers() throws Exception {
        // 첫 연결은 agent가 Lettuce·Netty 클래스를 처음 읽는 비용까지 더해져 500 ms 제한을 넘길 수 있다. 다음 시도에서 붙으면 완료다
        var client = mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> slow = mock(ConnectionFuture.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> ready = mock(ConnectionFuture.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisAsyncCommands<String, String> commands = mock(RedisAsyncCommands.class);
        RedisFuture<String> get = mock(RedisFuture.class);
        when(client.connectAsync(StringCodec.UTF8, ENDPOINT)).thenReturn(slow, ready);
        when(slow.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new ExecutionException(new RedisCommandTimeoutException("sensitive")));
        when(ready.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(connection);
        when(connection.async()).thenReturn(commands);
        when(commands.get("auth:session:{login}:by-id:warm-up")).thenReturn(get);
        when(get.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(null);

        new OpaqueSessionReader(client, ENDPOINT).warmUp(Duration.ofSeconds(5));

        // 시작·종료 줄은 하나씩이고, 종료 줄에 몇 번째에 붙었는지 남는다
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("session store warm-up start", "session store warm-up completed");
        assertThat(field(line("session store warm-up completed").orElseThrow(), "loresentry.result.attempts")).isEqualTo(2);
        verify(client, times(2)).connectAsync(StringCodec.UTF8, ENDPOINT);
        // 열리지 못한 첫 시도의 연결이 늦게 열리면 닫도록 걸어 둔다
        verify(slow).whenComplete(any());
        verify(connection).closeAsync();
    }

    @SuppressWarnings("unchecked")
    @Test void warmUpFailureDoesNotStopStartUp() throws Exception {
        var client = mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String, String>> connecting = mock(ConnectionFuture.class);
        when(client.connectAsync(StringCodec.UTF8, ENDPOINT)).thenReturn(connecting);
        when(connecting.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());

        assertThatCode(() -> new OpaqueSessionReader(client, ENDPOINT).warmUp(Duration.ofMillis(10))).doesNotThrowAnyException();
        // 정해 둔 횟수만큼 다시 시도한 뒤 실패 줄 하나를 남긴다(마지막 시도의 예외)
        verify(client, times(5)).connectAsync(StringCodec.UTF8, ENDPOINT);
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("session store warm-up start", "session store warm-up failed");
        var failed = line("session store warm-up failed").orElseThrow();
        assertThat(field(failed, "error.type")).isEqualTo("java.util.concurrent.TimeoutException");
        assertThat(field(failed, "loresentry.result.attempts")).isEqualTo(5);
        assertThat(field(failed, "error.stack_trace")).isNotNull();
        assertThat(failed.getThrowableProxy()).isNull();
    }
}
