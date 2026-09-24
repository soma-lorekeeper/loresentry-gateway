package com.loresentry.gateway.config;

import static org.assertj.core.api.Assertions.*;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentData;

class InternalHttpTransportTest {
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,false","true,false","false,true","true,true"})
    void disconnectOrTimeoutDoesNotRetryReadsOrWrites(boolean write, boolean timeout) throws Exception {
        try(var socket=new ServerSocket(0);
            var http=new InternalHttpConfiguration().internalHttpClient(Duration.ofMillis(200),Duration.ofMillis(200))) {
            socket.setSoTimeout(700);
            var count=new AtomicInteger();
            var worker=Thread.ofVirtual().start(()-> {
                try {
                    while(true) try(var accepted=socket.accept()) {
                        count.incrementAndGet();
                        var reader=new java.io.BufferedReader(new java.io.InputStreamReader(accepted.getInputStream()));
                        String line; while((line=reader.readLine())!=null&&!line.isEmpty()) { }
                        if (timeout) java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(450).toNanos());
                        // Close after request headers, before sending any response.
                    }
                } catch(SocketTimeoutException done) { }
                catch(java.io.IOException failure) { throw new RuntimeException(failure); }
            });
            var client=new ContentApiClient(RestClient.builder().baseUrl("http://127.0.0.1:"+socket.getLocalPort())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(http)).build());
            assertThatThrownBy(()-> {
                if(write) client.createProject(UUID.randomUUID(),new ContentData.ProjectInput("Novel",""),null);
                else client.listProjects(UUID.randomUUID(),null);
            }).hasMessage("CONTENT_UNAVAILABLE");
            worker.join(2000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(count).hasValue(1);
        }
    }
}
