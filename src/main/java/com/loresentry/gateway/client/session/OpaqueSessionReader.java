package com.loresentry.gateway.client.session;

import com.loresentry.gateway.application.SessionId;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Connection, preliminary GET and atomic revalidation/renewal share one 500 ms deadline. */
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
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        StatefulRedisConnection<String, String> connection = null;
        io.lettuce.core.ConnectionFuture<StatefulRedisConnection<String, String>> connecting = null;
        try {
            connecting = client.connectAsync(StringCodec.UTF8, writer);
            connection = connecting.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            String hash = id.hash(), idKey = "auth:session:{login}:by-id:" + hash;
            var get = connection.async().get(idKey);
            String raw = get.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            if (raw == null) throw new SessionLookupFailure(SessionLookupFailure.Kind.INVALID);
            UUID user = preliminaryUser(raw);
            remaining(deadline);
            var eval = connection.async().<List<String>>eval(SCRIPT, ScriptOutputType.MULTI,
                    new String[] { idKey, "auth:session:{login}:by-user:" + user }, user.toString(), hash);
            List<String> result = eval.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            if (result != null && result.equals(List.of("INVALID")))
                throw new SessionLookupFailure(SessionLookupFailure.Kind.INVALID);
            if (result == null || result.size() != 2 || !user.toString().equals(result.get(0))) throw unavailable();
            long expiry = Long.parseLong(result.get(1));
            if (expiry <= 1209600000L || expiry > 253402300799999L) throw unavailable();
            return new VerifiedSession(user, Instant.ofEpochMilli(expiry));
        } catch (SessionLookupFailure known) { throw known; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception failure) { throw unavailable(); }
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
