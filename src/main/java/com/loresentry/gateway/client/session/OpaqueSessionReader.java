package com.loresentry.gateway.client.session;

import com.loresentry.gateway.logging.LogLayer;
import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.logging.LogFlow;
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
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connection, preliminary GET and atomic revalidation/renewal share one 500 ms deadline.
 *
 * <p>저장소 확인 한 번(연결 → GET → EVAL)마다 저장소 호출 결과 줄 하나를 남긴다(LogFlow.call): 확인됨·세션 없음(INVALID, 정상적인
 * 거절)은 "valkey call completed", 확인할 수 없음(연결·명령 실패, 시간 초과, 저장소의 값이 이상함)은 "valkey call failed"(warn)와
 * 실패한 단계(param.stage)·이유(error.type)다. 이 실패는 SessionFilter에서 SESSION_UNAVAILABLE(503)이 되고, 그 결과 줄
 * (request completed)만으로는 원인을 알 수 없기 때문이다. 세션 ID·키 값은 인증 수단이라 남기지 않는다.
 */
// log.layer: 세션 저장소(Valkey)를 읽고 연장하므로 repository 레이어다(클래스 이름 끝 규칙에 맞지 않아 직접 지정)
@LogLayer("repository")
public final class OpaqueSessionReader {
    public record VerifiedSession(UUID userId, Instant expiresAt) {}
    /** 저장소 확인의 단계. 연결 → 세션 키 GET → 원자적 재확인·연장(EVAL) → 결과 검사 순서다. 실패 줄의 param.stage 허용 목록이다. */
    private static final Set<String> STAGES = Set.of("connect", "get", "eval", "result");
    /**
     * 기동 예열({@link #warmUp})을 다시 시도하는 횟수의 상한. 시도마다 연결·명령 시간 제한(500 ms, SessionRedisConfiguration)과
     * 처음 읽는 클래스를 준비하는 시간만큼 걸린다.
     */
    static final int WARM_UP_ATTEMPTS = 5;
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
        // 세션 ID와 그 hash는 인증 수단이라 남기지 않는다
        AppLogger.current().info("session store check");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        // 확인하지 못한 결과(INVALID·이유를 아는 UNAVAILABLE). 저장소 호출 결과 줄을 남긴 뒤 밖으로 던진다
        var rejected = new AtomicReference<SessionLookupFailure>();
        VerifiedSession verified;
        try {
            verified = LogFlow.call("valkey", call -> {
                // 실패 줄에 남길 단계. 연결 → 세션 키 GET → 원자적 재확인·연장(EVAL) → 결과 검사 순서다
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
                    // INVALID는 저장소가 답한 정상적인 거절이라 호출은 완료다. UNAVAILABLE은 500 ms를 넘겼거나(remaining)
                    // 저장소의 값이 규칙에 맞지 않을 때다. 그 이유와 단계를 실패 줄에 남긴다
                    if (known.kind() == SessionLookupFailure.Kind.UNAVAILABLE) call.result(LogField.code("stage", stage, STAGES))
                            .fail(System.nanoTime() >= deadline ? "DEADLINE_EXCEEDED" : "UNEXPECTED_STORE_VALUE");
                    rejected.set(known);
                    return null;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    call.result(LogField.code("stage", stage, STAGES));
                    throw interrupted;
                } catch (Exception failure) {
                    // 연결 시간 초과(TimeoutException), 연결·인증 실패(RedisConnectionException), 명령 오류(RedisCommandExecutionException) 등.
                    // 실패 줄(예외 종류·메시지 없는 스택)에 단계를 붙이고 그대로 던진다
                    call.result(LogField.code("stage", stage, STAGES));
                    throw failure;
                } finally {
                    if (connection != null) connection.closeAsync();
                    else if (connecting != null) connecting.whenComplete((late, error) -> { if (late != null) late.closeAsync(); });
                }
            });
        } catch (Exception failure) {
            // 실패 줄(valkey call failed)은 LogFlow가 남겼다
            throw unavailable();
        }
        if (rejected.get() != null) throw rejected.get();
        return verified;
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
     * 기동할 때 세션 저장소에 연결하고, 없는 세션 키를 한 번 읽는다(BFF 계정이 허용된 auth:session:* 의 GET만 쓴다).
     *
     * <p>세션 확인은 요청마다 새 연결을 열고 500 ms 안에 끝내야 한다. 그런데 파드의 첫 연결은 Netty 스레드·TLS 초기화와 JIT가
     * 한꺼번에 겹쳐 이 시간을 넘기고, 그러면 새 파드가 처음 받은 로그인 확인이 SESSION_UNAVAILABLE(503)이 된다. 이 비용을
     * 요청을 받기 전에 미리 치른다. 연결과 handshake(AUTH)에도 500 ms 제한이 있어(SessionRedisConfiguration), EDOT agent가 Lettuce·Netty
     * 클래스를 처음 읽으며 계측을 붙이는 비용까지 더해지면 첫 시도가 그 시간을 넘길 수 있다. 클래스가 준비될수록 다음 시도가 빨라지므로
     * 붙을 때까지 {@link #WARM_UP_ATTEMPTS}번까지 다시 시도한다(시작·종료 줄은 하나이고, 종료 줄의 result.attempts에 몇 번 시도했는지
     * 남긴다). 끝내 실패해도 예외를 밖으로 내지 않는다(저장소가 잠깐 없더라도 기동은 계속하고, 요청 때 다시 연결한다).
     *
     * @param timeout 시도마다 연결과 GET 각각을 기다릴 시간. 요청 때의 500 ms보다 넉넉하게 준다
     */
    public void warmUp(Duration timeout) {
        // 시작 줄 "session store warm-up start"와 종료 줄 "session store warm-up completed"(시도 횟수, 걸린 시간) 또는
        // "session store warm-up failed"(마지막 시도의 예외 종류·메시지 없는 스택)를 LogFlow가 남긴다
        try {
            LogFlow.task("session store warm-up", task -> {
                Exception last = null;
                for (int attempt = 1; attempt <= WARM_UP_ATTEMPTS; attempt++) {
                    StatefulRedisConnection<String, String> connection = null;
                    io.lettuce.core.ConnectionFuture<StatefulRedisConnection<String, String>> connecting = null;
                    try {
                        connecting = client.connectAsync(StringCodec.UTF8, writer);
                        connection = connecting.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                        connection.async().get("auth:session:{login}:by-id:warm-up").get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                        task.result(LogField.result("attempts", attempt));
                        return;
                    } catch (InterruptedException interrupted) {
                        // 기동이 멈추는 중이다. 더 시도하지 않고 실패 줄을 남긴다(인터럽트 표시는 아래 catch가 되살린다)
                        task.result(LogField.result("attempts", attempt));
                        throw interrupted;
                    } catch (Exception failure) {
                        last = failure;
                    } finally {
                        // 기다리다 그만둔 연결이 늦게 열리면 그때 닫는다(시도마다 연결이 하나씩 남지 않게)
                        if (connection != null) connection.closeAsync();
                        else if (connecting != null) connecting.whenComplete((late, error) -> { if (late != null) late.closeAsync(); });
                    }
                }
                task.result(LogField.result("attempts", WARM_UP_ATTEMPTS));
                throw last;
            });
        } catch (InterruptedException interrupted) {
            // 기동을 멈추지 않는다. 인터럽트 표시만 되살린다
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // 기동을 멈추지 않는다. 요청 때 다시 연결한다
        }
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
