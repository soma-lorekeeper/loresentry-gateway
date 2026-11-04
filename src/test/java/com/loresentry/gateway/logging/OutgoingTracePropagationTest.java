package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import com.loresentry.gateway.FakeChatUpstream;
import com.loresentry.gateway.LocalTestEnvironment;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.ChatStreamTransport;
import com.loresentry.gateway.support.AgentLikeTracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 내부 서비스를 부르는 호출에 지금 요청의 traceparent가 실리는지 확인한다(LOG_SCHEMA.md 7절 서비스 간 HTTP).
 * 그래서 content·authentication 같은 다음 서비스의 줄이 gateway와 같은 trace.id를 갖는다.
 *
 * <p>운영에서 헤더를 넣는 것은 EDOT agent의 Apache HttpClient 계측이다. 테스트에는 agent가 없어 같은 자리에서 같은 일을 하는 흉내
 * (support/AgentLikeTracing)를 쓴다. 그래서 이 테스트가 보는 것은 "호출이 요청의 문맥 안에서 나간다"이다. 우리 코드는 헤더를 넣지 않는다.
 */
@LocalTestEnvironment
@SpringBootTest
class OutgoingTracePropagationTest {
    /** 브라우저 요청의 서버 span 흉내를 여는 tracer. */
    private static final Tracer TRACER = SdkTracerProvider.builder().build().get("test");

    @Autowired RestClient.Builder builder;

    @Test
    void internalCallsCarryTheTraceparentOfTheCurrentRequest() throws Exception {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Span request = TRACER.spanBuilder("GET").setSpanKind(SpanKind.SERVER).startSpan();
        String trace = request.getSpanContext().getTraceId();
        try (Scope ignored = request.makeCurrent()) {
            server.expect(requestTo("http://content-api:8000/health"))
                    .andExpect(header("traceparent", startsWith("00-" + trace + "-")))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            builder.build().get().uri("http://content-api:8000/health").retrieve().body(String.class);

            // RestClient를 거치지 않는 호출(채팅 SSE의 Apache HttpClient)도 같은 trace로 나간다
            try (var upstream = FakeChatUpstream.start(exchange -> {
                    exchange.startEvents();
                    exchange.end();
                });
                 var http = HttpClients.custom().addRequestInterceptorLast(AgentLikeTracing.TRACEPARENT).build()) {
                var input = new ChatData.SendInput("질문", "explain", null, null, null, UUID.randomUUID());
                new ChatApiClient(null, new ChatStreamTransport(http, upstream.baseUrl()))
                        .send(UUID.randomUUID(), UUID.randomUUID(), input, null).close();
                assertThat(upstream.request().header("traceparent")).startsWith("00-" + trace + "-");
            }
        } finally {
            request.end();
        }
        server.verify();
    }
}
