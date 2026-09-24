package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import com.loresentry.gateway.config.SessionRedisConfiguration;
import com.loresentry.gateway.config.SessionRedisProperties;
import com.loresentry.gateway.client.session.SessionReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("redis")
class SessionRedisIntegrationTest {
    static RedisClient readerClient;
    static RedisClient adminClient;
    static Stream<Integer> ports() {
        return Stream.of("TEST_REDIS_PORT","TEST_VALKEY_PORT").map(name->Integer.parseInt(Objects.requireNonNull(System.getenv(name),name+" is required")));
    }
    @BeforeAll static void clients() {
        readerClient=new SessionRedisConfiguration().sessionRedisClient();adminClient=RedisClient.create();
    }
    @AfterAll static void close() {readerClient.shutdown();adminClient.shutdown();}
    RedisURI uri(int port) {return new SessionRedisConfiguration().sessionWriter(new SessionRedisProperties("127.0.0.1",port,"bff","bff-test-password",false));}
    StatefulRedisConnection<String,String> admin(int port) {return adminClient.connect(RedisURI.create("127.0.0.1",port));}
    SessionVerifier verifier(int port) {return new SessionVerifier(new SessionReader(readerClient,uri(port)),Clock.systemUTC());}
    AccessTokenVerifier.Claims claims(UUID user,UUID sid) {return new AccessTokenVerifier.Claims(user,sid,UUID.randomUUID(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(900));}
    String value(UUID sid,UUID refresh,long expiry) {return "{\"schema_version\":1,\"sid\":\""+sid+"\",\"refresh_jti\":\""+refresh+"\",\"refresh_expires_at\":"+expiry+"}";}
    long getCount(String info) {
        var matcher=java.util.regex.Pattern.compile("cmdstat_get:calls=(\\d+)").matcher(info);
        return matcher.find()?Long.parseLong(matcher.group(1)):0;
    }
    @ParameterizedTest @MethodSource("ports")
    void eachRequestReadsOnceAndRotationKeepsSessionWhileNewLoginRejectsOld(int port) {
        try(var admin=admin(port)) {
            var user=UUID.randomUUID();var sid=UUID.randomUUID();String key="auth:session:"+user;var request=claims(user,sid);
            admin.sync().set(key,value(sid,UUID.randomUUID(),Instant.now().plusSeconds(600).getEpochSecond()));admin.sync().pexpire(key,600000);
            long ttl=admin.sync().pttl(key),before=getCount(admin.sync().info("commandstats"));
            verifier(port).verify(request);
            admin.sync().set(key,value(sid,UUID.randomUUID(),Instant.now().plusSeconds(600).getEpochSecond()),SetArgs.Builder.keepttl());
            verifier(port).verify(request);
            assertThat(getCount(admin.sync().info("commandstats"))-before).isEqualTo(2);
            assertThat(admin.sync().pttl(key)).isLessThanOrEqualTo(ttl).isPositive();
            admin.sync().set(key,value(UUID.randomUUID(),UUID.randomUUID(),Instant.now().plusSeconds(600).getEpochSecond()));
            assertThatThrownBy(()->verifier(port).verify(request)).hasMessage("SESSION_INVALID");
            admin.sync().del(key);
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void missingExpiredAndMalformedRecordsFailClosed(int port) {
        try(var admin=admin(port)) {
            var user=UUID.randomUUID();var sid=UUID.randomUUID();String key="auth:session:"+user;var request=claims(user,sid);
            assertThatThrownBy(()->verifier(port).verify(request)).hasMessage("SESSION_INVALID");
            admin.sync().set(key,value(sid,UUID.randomUUID(),Instant.now().minusSeconds(1).getEpochSecond()));
            assertThatThrownBy(()->verifier(port).verify(request)).hasMessage("SESSION_INVALID");
            for(String raw:List.of("broken", "{\"schema_version\":99}", value(sid,UUID.randomUUID(),Instant.now().plusSeconds(30).getEpochSecond()).replace("refresh_jti","missing_jti"))) {
                admin.sync().set(key,raw);assertThatThrownBy(()->verifier(port).verify(request)).hasMessage("SESSION_UNAVAILABLE");
            }
            admin.sync().del(key);
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void dedicatedCredentialAllowsOnlySessionGetAndConnectionCommands(int port) {
        try(var bff=readerClient.connect(uri(port))) {
            assertThat(bff.sync().get("auth:session:"+UUID.randomUUID())).isNull();
            assertThatThrownBy(()->bff.sync().set("auth:session:test","x")).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().del("auth:session:test")).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().getdel("auth:session:test")).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().get("auth:oauth:test")).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().get("auth:refresh:test")).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().keys("*")).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().eval("return 1",ScriptOutputType.INTEGER)).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().expire("auth:session:test",60)).isInstanceOf(RedisCommandExecutionException.class);
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void aclDenialIsUnavailable(int port) {
        var endpoint=RedisURI.Builder.redis("127.0.0.1",port).withAuthentication("bff","incorrect-test-password").withTimeout(Duration.ofMillis(500)).build();
        var verifier=new SessionVerifier(new SessionReader(readerClient,endpoint),Clock.systemUTC());
        assertThatThrownBy(()->verifier.verify(claims(UUID.randomUUID(),UUID.randomUUID()))).hasMessage("SESSION_UNAVAILABLE");
    }
    @ParameterizedTest @MethodSource("ports")
    void stalledConnectionUsesOneShared500msDeadlineAndDoesNotReplay(int port) {
        try(var admin=admin(port)) {
            long before=getCount(admin.sync().info("commandstats"));
            admin.sync().clientPause(900);
            long start=System.nanoTime();
            assertThatThrownBy(()->verifier(port).verify(claims(UUID.randomUUID(),UUID.randomUUID()))).hasMessage("SESSION_UNAVAILABLE");
            long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();
            assertThat(elapsed).isBetween(400L,750L);
            // This command waits until the pause ends; a late connection must not send a GET.
            admin.sync().ping();
            assertThat(getCount(admin.sync().info("commandstats"))-before).isZero();
        }
    }
}
