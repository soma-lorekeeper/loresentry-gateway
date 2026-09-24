package com.loresentry.gateway.client.session;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;

/** One writer connection and one GET per request; no session cache, pool wait or replay. */
public class SessionReader {
    private static final long BUDGET_NANOS=TimeUnit.MILLISECONDS.toNanos(500);
    private final RedisClient client;
    private final RedisURI writer;
    public SessionReader(RedisClient client,RedisURI writer) { this.client=client;this.writer=writer; }
    public String get(UUID verifiedUser) {
        long deadline=System.nanoTime()+BUDGET_NANOS;
        var connecting=client.connectAsync(StringCodec.UTF8,writer);
        StatefulRedisConnection<String,String> connection=null;
        try {
            connection=connecting.get(remaining(deadline),TimeUnit.NANOSECONDS);
            remaining(deadline);
            var pending=connection.async().get("auth:session:"+verifiedUser);
            String value=pending.get(remaining(deadline),TimeUnit.NANOSECONDS);
            remaining(deadline);
            return value;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new SessionReadFailure();
        } catch(Exception unavailable) { throw new SessionReadFailure(); }
        finally {
            if(connection!=null) connection.closeAsync();
            else connecting.whenComplete((lateConnection,error)->{if(lateConnection!=null) lateConnection.closeAsync();});
        }
    }
    private static long remaining(long deadline) {
        long remaining=deadline-System.nanoTime();
        if(remaining<=0) throw new SessionReadFailure();
        return remaining;
    }
}
