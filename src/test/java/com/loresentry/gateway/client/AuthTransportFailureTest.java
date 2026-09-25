package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.loresentry.gateway.application.SessionService;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.config.InternalHttpConfiguration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

class AuthTransportFailureTest {
    @Test void refusedConnectionIsDefinitelyNotSent() throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        try(var transport=new InternalHttpConfiguration().internalHttpClient(Duration.ofMillis(200),Duration.ofMillis(200))) {
            var service=new SessionService(new AuthApiClient(RestClient.builder().baseUrl("http://127.0.0.1:"+port)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(transport)).build()));
            assertThat(service.revoke("A".repeat(43))).isEqualTo(SessionService.Revocation.UNCONFIRMED);
        }
    }
    @Test void lostResponseIsUnknownAndPostIsSentOnce() throws Exception {
        try(var socket=new ServerSocket(0);var transport=new InternalHttpConfiguration().internalHttpClient(Duration.ofMillis(200),Duration.ofMillis(200))) {
            socket.setSoTimeout(700);var requests=new AtomicInteger();
            var server=Thread.ofVirtual().start(()-> {
                try {while(true)try(var connection=socket.accept()) {
                    requests.incrementAndGet();var input=new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream()));
                    String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                }}catch(SocketTimeoutException done){}catch(java.io.IOException failure){throw new RuntimeException(failure);}
            });
            var service=new SessionService(new AuthApiClient(RestClient.builder().baseUrl("http://127.0.0.1:"+socket.getLocalPort())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(transport)).build()));
            assertThat(service.revoke("A".repeat(43))).isEqualTo(SessionService.Revocation.UNCONFIRMED);
            server.join(2000);assertThat(requests).hasValue(1);assertThat(server.isAlive()).isFalse();
        }
    }
}
