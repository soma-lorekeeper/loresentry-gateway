package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.client.session.OpaqueSessionReader;
import com.loresentry.gateway.config.SessionRedisConfiguration;
import io.lettuce.core.*;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("redis")
class OpaqueSessionIntegrationTest {
    static RedisClient client, adminClient;
    static String script;
    static final String PREFIX = "auth:session:{login}:";
    static Stream<Integer> ports() {
        return Stream.of("TEST_REDIS_PORT", "TEST_VALKEY_PORT")
                .map(name -> Integer.parseInt(Objects.requireNonNull(System.getenv(name))));
    }
    @BeforeAll static void start() throws Exception {
        client = new SessionRedisConfiguration().sessionRedisClient();
        adminClient = RedisClient.create();
        try (var input = OpaqueSessionIntegrationTest.class.getResourceAsStream("/redis/verify-session.lua")) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        ports().forEach(port -> {
            try (var admin = adminClient.connect(RedisURI.create("127.0.0.1", port))) {
                admin.sync().aclSetuser("bff-session", new AclSetuserArgs().reset().on()
                        .addPassword("test-session-password").keyPattern("auth:session:{login}:*")
                        .addCommand(CommandType.GET).addCommand(CommandType.EVAL)
                        .addCommand(CommandType.TIME).addCommand(CommandType.PTTL)
                        .addCommand(CommandType.PEXPIREAT).addCommand(CommandType.HELLO)
                        .addCommand(CommandType.PING)
                        .addCommand(CommandType.CLIENT, io.lettuce.core.protocol.CommandKeyword.SETINFO));
            }
        });
    }
    @AfterAll static void close() { client.shutdown(); adminClient.shutdown(); }
    StatefulRedisConnection<String,String> admin(int port) {
        return adminClient.connect(RedisURI.create("127.0.0.1", port));
    }
    RedisURI endpoint(int port) { return RedisURI.Builder.redis("127.0.0.1", port)
            .withAuthentication("bff-session", "test-session-password").build(); }
    OpaqueSessionVerifier verifier(int port) {
        return new OpaqueSessionVerifier(new OpaqueSessionReader(client, endpoint(port)));
    }
    SessionId id() { byte[] value = new byte[32]; new java.security.SecureRandom().nextBytes(value);
        return new SessionId(Base64.getUrlEncoder().withoutPadding().encodeToString(value)); }
    String idKey(SessionId id) { return PREFIX + "by-id:" + id.hash(); }
    String userKey(UUID user) { return PREFIX + "by-user:" + user; }
    void seed(StatefulRedisConnection<String,String> admin, UUID user, SessionId id, long ttl) {
        admin.sync().eval("local t=redis.call('TIME'); local e=t[1]*1000+math.floor(t[2]/1000)+tonumber(ARGV[3]); "
                + "redis.call('SET',KEYS[1],ARGV[1],'PXAT',string.format('%.0f',e)); "
                + "redis.call('SET',KEYS[2],ARGV[2],'PXAT',string.format('%.0f',e)); return 1",
                ScriptOutputType.INTEGER, new String[]{idKey(id),userKey(user)},
                "{\"schema_version\":2,\"user_id\":\""+user+"\",\"created_at\":1}",
                "{\"schema_version\":2,\"session_hash\":\""+id.hash()+"\"}", Long.toString(ttl));
    }
    @ParameterizedTest @MethodSource("ports")
    void renewsBothTtlsFromServerTimeAndAllowsRepeatedActivity(int port) {
        try (var admin=admin(port)) {
            var user=UUID.randomUUID(); var id=id(); seed(admin,user,id,30000);
            var verified=verifier(port).verify(id.value());
            assertThat(verified.userId()).isEqualTo(user);
            assertThat(verified.expiresAt()).isBetween(Instant.now().plusSeconds(1209598), Instant.now().plusSeconds(1209601));
            assertThat(admin.sync().pexpiretime(idKey(id))).isEqualTo(verified.expiresAt().toEpochMilli());
            assertThat(admin.sync().pexpiretime(userKey(user))).isEqualTo(verified.expiresAt().toEpochMilli());
            assertThat(verifier(port).verify(id.value()).userId()).isEqualTo(user);
            assertThat(admin.sync().get(idKey(id))).doesNotContain(id.value());
            admin.sync().del(idKey(id),userKey(user));
            assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_INVALID");
            assertThat(admin.sync().exists(idKey(id),userKey(user))).isZero();
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void oldLookupCannotAuthenticateAfterNewLoginOrRevocation(int port) {
        try (var admin=admin(port)) {
            var user=UUID.randomUUID(); var old=id(); seed(admin,user,old,30000);
            String preliminary=admin.sync().get(idKey(old));
            assertThat(preliminary).contains(user.toString());
            var next=id();seed(admin,user,next,30000);
            List<String> result=admin.sync().eval(script,ScriptOutputType.MULTI,
                    new String[]{idKey(old),userKey(user)},user.toString(),old.hash());
            assertThat(result).containsExactly("INVALID");
            assertThat(admin.sync().pttl(idKey(old))).isLessThanOrEqualTo(30000);
            assertThatThrownBy(()->verifier(port).verify(old.value())).hasMessage("SESSION_INVALID");
            admin.sync().del(idKey(next),userKey(user));
            List<String> afterDelete=admin.sync().eval(script,ScriptOutputType.MULTI,
                    new String[]{idKey(next),userKey(user)},user.toString(),next.hash());
            assertThat(afterDelete).containsExactly("INVALID");
            assertThat(admin.sync().exists(idKey(next),userKey(user))).isZero();
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void absentAndExpiredAreInvalidWhileCorruptionAndUnequalTtlsAreUnavailable(int port) {
        try(var admin=admin(port)) {
            var user=UUID.randomUUID();var id=id();
            assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_INVALID");
            seed(admin,user,id,30000);admin.sync().pexpireat(idKey(id),1);
            assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_INVALID");
            seed(admin,user,id,30000);admin.sync().pexpire(idKey(id),10000);
            assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_UNAVAILABLE");
            seed(admin,user,id,30000);admin.sync().persist(userKey(user));
            assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_UNAVAILABLE");
            for(String raw:List.of("broken","{}","{\"schema_version\":2,\"session_hash\":\""+id.hash()+"\",\"schema_version\":2}",
                    "{\"schema_version\":2,\"session_hash\":\""+id.hash()+"\",\"extra\":1}")) {
                seed(admin,user,id,30000);admin.sync().set(userKey(user),raw,SetArgs.Builder.keepttl());
                assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_UNAVAILABLE");
                assertThat(admin.sync().pttl(idKey(id))).isLessThanOrEqualTo(30000);
            }
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void credentialCannotCreateDeleteOrReadOauthAndAclFailureCannotAuthenticate(int port) {
        try(var bff=client.connect(endpoint(port));var admin=admin(port)) {
            String key=PREFIX+"by-id:"+id().hash();
            assertThatThrownBy(()->bff.sync().set(key,"x")).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().del(key)).isInstanceOf(RedisCommandExecutionException.class);
            assertThatThrownBy(()->bff.sync().get("auth:oauth:test")).isInstanceOf(RedisCommandExecutionException.class);
            var user=UUID.randomUUID();var id=id();seed(admin,user,id,30000);
            admin.sync().aclSetuser("bff-session",new AclSetuserArgs().removeCommand(CommandType.PEXPIREAT));
            try { assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_UNAVAILABLE"); }
            finally { admin.sync().aclSetuser("bff-session",new AclSetuserArgs().addCommand(CommandType.PEXPIREAT)); }
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void connectionStallStaysInsideOneDeadlineAndDoesNotRenewLater(int port) {
        try(var admin=admin(port)) {
            var user=UUID.randomUUID();var id=id();seed(admin,user,id,30000);
            admin.sync().clientPause(1000);
            long before=System.nanoTime();
            assertThatThrownBy(()->verifier(port).verify(id.value())).hasMessage("SESSION_UNAVAILABLE");
            assertThat(Duration.ofNanos(System.nanoTime()-before).toMillis()).isBetween(350L,800L);
            admin.sync().ping();
            assertThat(admin.sync().pttl(idKey(id))).isLessThanOrEqualTo(30000);
            assertThat(admin.sync().pttl(userKey(user))).isLessThanOrEqualTo(30000);
        }
    }
    @ParameterizedTest @MethodSource("ports")
    void concurrentActivityPreservesOneIdAndEqualExpiry(int port) throws Exception {
        try(var admin=admin(port);var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var user=UUID.randomUUID();var id=id();seed(admin,user,id,30000);
            var gate=new java.util.concurrent.CountDownLatch(1);
            var results=new ArrayList<java.util.concurrent.Future<OpaqueSessionReader.VerifiedSession>>();
            for(int i=0;i<6;i++) results.add(executor.submit(()->{gate.await();return verifier(port).verify(id.value());}));
            gate.countDown();
            for(var result:results) assertThat(result.get(3,java.util.concurrent.TimeUnit.SECONDS).userId()).isEqualTo(user);
            assertThat(admin.sync().pexpiretime(idKey(id))).isEqualTo(admin.sync().pexpiretime(userKey(user)));
            assertThat(admin.sync().get(userKey(user))).contains(id.hash());
        }
    }
}
