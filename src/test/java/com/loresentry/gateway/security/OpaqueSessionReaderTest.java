package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.client.session.OpaqueSessionReader;
import io.lettuce.core.*;
import io.lettuce.core.api.*;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OpaqueSessionReaderTest {
    @SuppressWarnings("unchecked")
    @Test void lostEvalResponseIsUnavailableAndNeverRetried() throws Exception {
        var client=mock(RedisClient.class);
        ConnectionFuture<StatefulRedisConnection<String,String>> connecting=mock(ConnectionFuture.class);
        StatefulRedisConnection<String,String> connection=mock(StatefulRedisConnection.class);
        RedisAsyncCommands<String,String> commands=mock(RedisAsyncCommands.class);
        RedisFuture<String> get=mock(RedisFuture.class);
        RedisFuture<List<String>> eval=mock(RedisFuture.class);
        var endpoint=RedisURI.create("localhost",6379);
        var user=UUID.randomUUID();
        when(client.connectAsync(StringCodec.UTF8,endpoint)).thenReturn(connecting);
        when(connecting.get(anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn(connection);
        when(connection.async()).thenReturn(commands);
        when(commands.get(anyString())).thenReturn(get);
        when(get.get(anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn("{\"schema_version\":2,\"user_id\":\""+user+"\",\"created_at\":1}");
        when(commands.<List<String>>eval(anyString(),eq(ScriptOutputType.MULTI),any(String[].class),any(String[].class))).thenReturn(eval);
        when(eval.get(anyLong(),eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException("sensitive-response"));
        assertThatThrownBy(()->new OpaqueSessionVerifier(new OpaqueSessionReader(client,endpoint)).verify("A".repeat(43)))
                .hasMessage("SESSION_UNAVAILABLE").hasNoCause();
        verify(commands,times(1)).eval(anyString(),eq(ScriptOutputType.MULTI),any(String[].class),any(String[].class));
        verify(connection).closeAsync();
    }
}
