package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.HashMap;
import java.util.Map;
import com.loresentry.gateway.LocalTestEnvironment;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
 * <p>span 전송(OTLP)을 꺼 둔 운영 설정 그대로 확인한다. Boot 4는 전파기를 tracing export 설정에 묶어 두어,
 * 전체 export를 끄면 전파기가 noop이 된다. 그래서 전체는 켜고 OTLP만 끈다(application.yaml).
 */
@LocalTestEnvironment
@SpringBootTest
class OutgoingTracePropagationTest {
    @Autowired RestClient.Builder builder;
    @Autowired Tracer tracer;
    @Autowired TracePropagation propagation;

    @Test
    void internalCallsCarryTheTraceparentOfTheCurrentRequest() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Span span = tracer.nextSpan().name("browser request").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            server.expect(requestTo("http://content-api:8000/health"))
                    .andExpect(header("traceparent", startsWith("00-" + span.context().traceId() + "-")))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            builder.build().get().uri("http://content-api:8000/health").retrieve().body(String.class);

            // RestClient를 거치지 않는 호출(채팅 SSE)도 같은 trace를 직접 싣는다
            Map<String, String> headers = new HashMap<>();
            propagation.inject(headers::put);
            assertThat(headers.get("traceparent")).startsWith("00-" + span.context().traceId() + "-");
        } finally {
            span.end();
        }
        server.verify();
    }
}
