package com.loresentry.gateway.client.session;

import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connection, preliminary GET and atomic revalidation/renewal share one 500 ms deadline.
 *
 * <p>세션을 확인할 수 없으면(연결·명령 실패, 시간 초과, 저장소의 값이 이상함) 어느 단계에서 왜 실패했는지 warn으로 남긴다.
 * 이 실패는 SessionFilter에서 SESSION_UNAVAILABLE(503)이 되고, 그 결과 줄(request completed)만으로는 원인을 알 수 없기 때문이다.
 * 세션이 없거나 만료된 경우(INVALID)는 정상적인 거절이라 남기지 않는다. 세션 ID·키 값은 인증 수단이라 남기지 않는다.
 */
public final class OpaqueSessionReader {
    public record VerifiedSession(UUID userId, Instant expiresAt) {}
    private static final String SCRIPT = script();
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final RedisClient client;
    private final RedisURI writer;

    public OpaqueSessionReader(RedisClient client, RedisURI writer) {
        this.client = client; this.writer = writer;
    }

    public VerifiedSession verifyAndExtend(SessionId id) {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(500);
        // 실패 로그에 남길 단계. 연결 → 세션 키 GET → 원자적 재확인·연장(EVAL) → 결과 검사 순서다
        String stage = "connect";
        StatefulRedisConnection<String, String> connection = null;
        io.lettuce.core.ConnectionFuture<StatefulRedisConnection<String, String>> connecting = null;
        try {
            connecting = client.connectAsync(StringCodec.UTF8, writer);
            connection = connecting.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            stage = "get";
            String hash = id.hash(), idKey = "auth:session:{login}:by-id:" + hash;
            var get = connection.async().get(idKey);
            String raw = get.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            if (raw == null) throw new SessionLookupFailure(SessionLookupFailure.Kind.INVALID);
            UUID user = preliminaryUser(raw);
            remaining(deadline);
            stage = "eval";
            var eval = connection.async().<List<String>>eval(SCRIPT, ScriptOutputType.MULTI,
                    new String[] { idKey, "auth:session:{login}:by-user:" + user }, user.toString(), hash);
            List<String> result = eval.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            stage = "result";
            if (result != null && result.equals(List.of("INVALID")))
                throw new SessionLookupFailure(SessionLookupFailure.Kind.INVALID);
            if (result == null || result.size() != 2 || !user.toString().equals(result.get(0))) throw unavailable();
            long expiry = Long.parseLong(result.get(1));
            if (expiry <= 1209600000L || expiry > 253402300799999L) throw unavailable();
            return new VerifiedSession(user, Instant.ofEpochMilli(expiry));
        } catch (SessionLookupFailure known) {
            // UNAVAILABLE은 500 ms를 넘겼거나(remaining) 저장소의 값이 규칙에 맞지 않을 때다. INVALID는 남기지 않는다
            if (known.kind() == SessionLookupFailure.Kind.UNAVAILABLE)
                failed(stage, System.nanoTime() >= deadline ? "DEADLINE_EXCEEDED" : "UNEXPECTED_STORE_VALUE", started);
            throw known;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failed(stage, interrupted.getClass().getName(), started);
            throw unavailable();
        }
        catch (Exception failure) {
            // 연결 시간 초과(TimeoutException), 연결·인증 실패(RedisConnectionException), 명령 오류(RedisCommandExecutionException) 등
            failed(stage, failure.getClass().getName(), started);
            throw unavailable();
        }
        finally {
            if (connection != null) connection.closeAsync();
            else if (connecting != null) connecting.whenComplete((late, error) -> { if (late != null) late.closeAsync(); });
        }
    }

    private static UUID preliminaryUser(String raw) {
        if (raw.length() > 16384) throw unavailable();
        var node = JSON.readTree(raw);
        if (!node.isObject() || !node.propertyNames().equals(Set.of("schema_version", "user_id", "created_at"))
                || !node.get("schema_version").isIntegralNumber() || node.get("schema_version").asLong() != 2
                || !node.get("user_id").isString() || !node.get("created_at").isIntegralNumber()
                || !node.get("created_at").canConvertToLong() || node.get("created_at").asLong() <= 0
                || node.get("created_at").asLong() > 253402300799L) throw unavailable();
        String value = node.get("user_id").asString();
        UUID user = UUID.fromString(value);
        if (!user.toString().equals(value)) throw unavailable();
        return user;
    }

    /**
     * 기동할 때 세션 저장소에 한 번 연결하고, 없는 세션 키를 한 번 읽는다(BFF 계정이 허용된 auth:session:* 의 GET만 쓴다).
     *
     * <p>세션 확인은 요청마다 새 연결을 열고 500 ms 안에 끝내야 한다. 그런데 파드의 첫 연결은 Netty 스레드·TLS 초기화와 JIT가
     * 한꺼번에 겹쳐 이 시간을 넘기고, 그러면 새 파드가 처음 받은 로그인 확인이 SESSION_UNAVAILABLE(503)이 된다. 이 비용을
     * 요청을 받기 전에 미리 치른다. 실패해도 예외를 밖으로 내지 않는다(저장소가 잠깐 없더라도 기동은 계속하고, 요청 때 다시 연결한다).
     *
     * @param timeout 연결과 GET 각각을 기다릴 시간. 요청 때의 500 ms보다 넉넉하게 준다
     */
    public void warmUp(Duration timeout) {
        long started = System.nanoTime();
        StatefulRedisConnection<String, String> connection = null;
        try {
            connection = client.connectAsync(StringCodec.UTF8, writer).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            connection.async().get("auth:session:{login}:by-id:warm-up").get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            AppLogger.current().info("session store connection warmed up", LogField.duration(Duration.ofNanos(System.nanoTime() - started)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            AppLogger.current().warn("session store warm-up failed", LogField.errorType(interrupted.getClass().getName()),
                    LogField.duration(Duration.ofNanos(System.nanoTime() - started)));
        } catch (Exception failure) {
            AppLogger.current().warn("session store warm-up failed", LogField.errorType(failure.getClass().getName()),
                    LogField.duration(Duration.ofNanos(System.nanoTime() - started)));
        } finally {
            if (connection != null) connection.closeAsync();
        }
    }

    /** 세션 확인 실패의 원인 줄. 단계(connect·get·eval·result)는 고정된 낱말이라 문구에 넣는다. */
    private static void failed(String stage, String type, long started) {
        AppLogger.current().warn("session store check failed during " + stage, LogField.errorType(type),
                LogField.duration(Duration.ofNanos(System.nanoTime() - started)));
    }

    private static long remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw unavailable();
        return remaining;
    }
    private static SessionLookupFailure unavailable() {
        return new SessionLookupFailure(SessionLookupFailure.Kind.UNAVAILABLE);
    }
    private static String script() {
        try (var input = OpaqueSessionReader.class.getResourceAsStream("/redis/verify-session.lua")) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
