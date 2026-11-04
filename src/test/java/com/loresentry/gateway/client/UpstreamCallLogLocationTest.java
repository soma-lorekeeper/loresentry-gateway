package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loresentry.gateway.logging.LoggingConfiguration;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 내부 서비스 호출 결과 줄(upstream call completed)의 위치와 주소 필드를 확인한다(LOG_SCHEMA.md 5절).
 * 우리 client 코드가 RestClient를 부르는 상황이어야 해서 logging 패키지 밖(client 패키지)에 둔다.
 * <ul>
 *   <li>log.logger·log.origin.function은 실제로 HTTP를 보낸 Spring RestClient 메서드다(호출한 우리 메서드는 앞 진입 줄에 있다)</li>
 *   <li>log.layer는 client다</li>
 *   <li>내부 서비스 주소는 url.full이고 쿼리는 빠진다. url.path는 브라우저 요청의 값이라 여기서는 없다</li>
 *   <li>호출은 upstream call span 안에서 나가고 결과 줄도 그 span 안에서 찍힌다. 실패면 span 상태가 ERROR다(LOG_SCHEMA.md 7절 "외부 호출")</li>
 * </ul>
 */
class UpstreamCallLogLocationTest {
    /** interceptor가 여는 span을 받는 OpenTelemetry SDK. 운영에서는 agent의 SDK가 받는다. 이 테스트 클래스 동안 GlobalOpenTelemetry가 된다. */
    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    /** 줄을 찍은 순간의 지금 span. formatter는 줄을 쓰는 순간 이 span으로 trace.id·span.id를 채운다. */
    private final Map<ILoggingEvent, SpanContext> spanAtLine = new IdentityHashMap<>();

    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            spanAtLine.put(event, Span.current().getSpanContext());
            super.append(event);
        }
    };
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach void attach() { events.start(); root.addAppender(events); }
    @AfterEach void detach() { root.detachAppender(events); }

    @Test
    void theCallLineIsLocatedAtTheRestClientMethodAndKeepsTheCalledUrlWithoutQuery() {
        // 운영과 같은 interceptor를 RestClient.Builder에 붙이고, 응답은 가짜 서버가 준다
        RestClient.Builder builder = RestClient.builder();
        new LoggingConfiguration().upstreamCallLogging().customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://content-api:8080/files/f-1/content?q=secret")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        builder.build().get().uri("http://content-api:8080/files/f-1/content?q=secret").retrieve().body(String.class);

        ILoggingEvent line = events.list.stream().filter(e -> "upstream call completed".equals(e.getFormattedMessage())).findFirst().orElseThrow();
        Map<String, Object> pairs = new HashMap<>();
        line.getKeyValuePairs().forEach(p -> pairs.putIfAbsent(p.key, p.value));
        assertThat((String) pairs.get("log.logger")).startsWith("org.springframework.web.client.DefaultRestClient");
        assertThat(pairs).containsEntry("log.origin.function", "body").containsEntry("log.layer", "client")
                .containsEntry("log.package", "org.springframework.web.client")
                .containsEntry("url.domain", "content-api").containsEntry("url.full", "http://content-api:8080/files/f-1/content")
                .doesNotContainKey("url.path");
        // 레벨 설정은 우리 interceptor 기준이다(Spring 패키지의 로그 레벨에 묶이지 않는다)
        assertThat(line.getLoggerName()).isEqualTo("com.loresentry.gateway.logging.UpstreamCallLogInterceptor");
        server.verify();
    }

    @Test
    void theCallIsSentInsideAnUpstreamCallSpanAndItsLineIsWrittenThere() {
        // agent의 Apache HttpClient 계측은 RestClient interceptor보다 안쪽(실제로 보내는 곳)에서 CLIENT span을 열고 traceparent를 넣는다.
        // 그 자리에서 지금 span을 기록해, CLIENT span의 부모가 upstream call span이 되는지 본다
        RestClient.Builder builder = RestClient.builder();
        new LoggingConfiguration().upstreamCallLogging().customize(builder);
        String[] sentIn = new String[1];
        builder.requestInterceptor((request, body, execution) -> {
            sentIn[0] = Span.current().getSpanContext().getSpanId();
            return execution.execute(request, body);
        });
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://content-api:8080/files/f-1/content")).andRespond(withServerError());

        assertThatThrownBy(() -> builder.build().get().uri("http://content-api:8080/files/f-1/content").retrieve().body(String.class))
                .isInstanceOf(HttpServerErrorException.class);

        SpanData upstream = span("upstream call");
        assertThat(sentIn[0]).isEqualTo(upstream.getSpanId());
        assertThat(spanAtLine.get(line("upstream call completed")).getSpanId()).isEqualTo(upstream.getSpanId());
        // 대상의 5xx는 span 상태도 ERROR이고 설명이 결과 줄의 error.type과 같다
        assertThat(upstream.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(upstream.getStatus().getDescription()).isEqualTo("UPSTREAM_SERVER_ERROR");
        server.verify();
    }

    @Test
    void aConnectionFailureIsWrittenInsideTheSpanAndMarksIt() {
        RestClient.Builder builder = RestClient.builder();
        new LoggingConfiguration().upstreamCallLogging().customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://content-api:8080/health")).andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> builder.build().get().uri("http://content-api:8080/health").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);

        SpanData upstream = span("upstream call");
        assertThat(spanAtLine.get(line("upstream call failed")).getSpanId()).isEqualTo(upstream.getSpanId());
        assertThat(upstream.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(upstream.getStatus().getDescription()).isEqualTo("java.io.IOException");
    }

    private ILoggingEvent line(String message) {
        return events.list.stream().filter(e -> message.equals(e.getFormattedMessage())).findFirst().orElseThrow();
    }

    /** 이 테스트에서 끝난 span 가운데 그 이름의 것. */
    private static SpanData span(String name) {
        return OTEL.getSpans().stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name));
    }
}
