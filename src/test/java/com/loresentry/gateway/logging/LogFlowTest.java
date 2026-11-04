package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 로깅 팩토리 {@link LogFlow}와 {@link AppLogger#identify}가 흐름의 줄을 규칙대로 남기는지 확인한다(LOG_SCHEMA.md 5절 "만들기").
 * <ul>
 *   <li>외부 호출은 결과 줄 하나다: 완료(응답 상태·걸린 시간·주소), 상대의 5xx는 warn과 UPSTREAM_SERVER_ERROR, 예외는 실패 줄 뒤 다시 던진다</li>
 *   <li>별도 작업은 시작·종료 줄이 짝을 이루고, 할 일이 예외로 끝나도 종료(실패) 줄이 남는다</li>
 *   <li>종료 줄에 걸린 시간(event.duration, 나노초 숫자)과 결과 값이 붙고, 실패 줄에는 예외 메시지가 없다</li>
 *   <li>줄의 위치(log.logger·log.origin.function·log.layer)는 흐름을 연 client·service 메서드다</li>
 *   <li>진행 중에 알게 된 대상 ID(with)와 사용자(identify)가 이후 줄과 종료·결과 줄에 붙는다</li>
 *   <li>외부 호출·작업은 로깅 팩토리가 span을 열고, 그 줄이 그 span 안에서 찍힌다. 실패면 span 상태가 ERROR다(LOG_SCHEMA.md 7절)</li>
 * </ul>
 */
class LogFlowTest {
    /**
     * 로깅 팩토리가 여는 span을 받는 OpenTelemetry SDK. 운영에서는 agent의 SDK가 받는다. 이 테스트 클래스 동안 GlobalOpenTelemetry가 되고,
     * 테스트마다 받은 span을 비운다.
     */
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

    private static Map<String, Object> pairs(ILoggingEvent event) {
        Map<String, Object> out = new HashMap<>();
        if (event.getKeyValuePairs() != null) event.getKeyValuePairs().forEach(p -> out.putIfAbsent(p.key, p.value));
        return out;
    }

    private List<String> messages() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ILoggingEvent line(String message) {
        return events.list.stream().filter(e -> message.equals(e.getFormattedMessage())).findFirst().orElseThrow();
    }

    /** 외부 호출을 하는 client 흉내. 이름 끝 규칙으로 client 레이어다. 결과 줄이 이 클래스 자리에 남는지 본다. */
    static final class SampleStreamClient {
        String open(int status) {
            return LogFlow.call("upstream", URI.create("http://ai-chat-api:8080/chat/sessions/s-1/messages?q=secret"), call -> {
                call.status(status);
                return "opened";
            });
        }

        void broken() throws IOException {
            LogFlow.call("valkey", call -> {
                throw new IOException("secret host in message");
            });
        }
    }

    /** 별도 작업을 하는 service 흉내. */
    static final class SampleRelayService {
        void relay(UUID session) {
            LogFlow.task("sample relay", relay -> {
                relay.with(LogField.chatSessionId(session));
                AppLogger.current().info("sample chunk relayed");
                relay.result(LogField.result("bytes", 12L));
            }, LogField.param("attempt", 1));
        }

        void crash() {
            LogFlow.task("sample relay", relay -> {
                throw new IllegalStateException("secret body in message");
            });
        }
    }

    @Test
    void aCallWritesOneResultLineAtTheClientMethodWithTheCalledAddress() {
        assertThat(new SampleStreamClient().open(200)).isEqualTo("opened");
        assertThat(messages()).containsExactly("upstream call completed");
        ILoggingEvent completed = line("upstream call completed");
        assertThat(completed.getLevel().toString()).isEqualTo("INFO");
        assertThat(completed.getLoggerName()).endsWith("LogFlowTest$SampleStreamClient");
        Map<String, Object> fields = pairs(completed);
        assertThat(fields).containsEntry("http.response.status_code", 200L).containsEntry("url.domain", "ai-chat-api")
                // 쿼리는 주소에 남지 않는다
                .containsEntry("url.full", "http://ai-chat-api:8080/chat/sessions/s-1/messages")
                .containsEntry("log.layer", "client").containsEntry("log.origin.function", "open")
                .doesNotContainKey("error.type").doesNotContainKey("log.logger");
        assertThat(fields.get("event.duration")).isInstanceOf(Long.class);
    }

    @Test
    void aServerErrorIsAWarningWithItsReason() {
        new SampleStreamClient().open(503);
        ILoggingEvent completed = line("upstream call completed");
        assertThat(completed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(completed)).containsEntry("error.type", "UPSTREAM_SERVER_ERROR").containsEntry("http.response.status_code", 503L);
    }

    @Test
    void aFailingCallRethrowsAfterAFailureLineWithoutTheMessage() {
        // 검사 예외도 그대로 다시 던진다
        assertThatThrownBy(() -> new SampleStreamClient().broken()).isInstanceOf(IOException.class);
        ILoggingEvent failed = line("valkey call failed");
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        Map<String, Object> fields = pairs(failed);
        assertThat(fields).containsEntry("error.type", "java.io.IOException").containsEntry("log.origin.function", "broken");
        assertThat(fields.get("event.duration")).isInstanceOf(Long.class);
        assertThat(String.valueOf(fields.get("error.stack_trace"))).startsWith("java.io.IOException\n").doesNotContain("secret host");
        assertThat(failed.getThrowableProxy()).isNull();

        // 예외 없이 알린 실패도 실패 줄이다
        events.list.clear();
        LogFlow.call("valkey", call -> call.fail("DEADLINE_EXCEEDED"));
        assertThat(pairs(line("valkey call failed"))).containsEntry("error.type", "DEADLINE_EXCEEDED").doesNotContainKey("error.stack_trace");
    }

    @Test
    void aTaskWritesStartAndEndLinesAndTheIdsFoundOnTheWay() {
        UUID session = UUID.randomUUID();
        new SampleRelayService().relay(session);
        assertThat(messages()).containsExactly("sample relay start", "sample chunk relayed", "sample relay completed");
        assertThat(pairs(line("sample relay start"))).containsEntry("loresentry.param.attempt", 1)
                .containsEntry("log.layer", "service").containsEntry("log.origin.function", "relay");
        // 진행 중에 알게 된 대상 ID가 이후 줄과 종료 줄에 붙는다
        assertThat(pairs(line("sample chunk relayed"))).containsEntry("loresentry.chat.session_id", session.toString());
        Map<String, Object> end = pairs(line("sample relay completed"));
        assertThat(end).containsEntry("loresentry.chat.session_id", session.toString()).containsEntry("loresentry.result.bytes", 12L)
                .containsEntry("log.layer", "service");
        assertThat(end.get("event.duration")).isInstanceOf(Long.class);
        assertThat(line("sample relay completed").getLoggerName()).endsWith("LogFlowTest$SampleRelayService");

        events.list.clear();
        LogFlow.task("sample relay", relay -> relay.fail("AI_CHAT_STREAM_INTERRUPTED"));
        ILoggingEvent failed = line("sample relay failed");
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(failed)).containsEntry("error.type", "AI_CHAT_STREAM_INTERRUPTED");
    }

    @Test
    void aFailingTaskStillWritesItsEndLineAndRethrows() {
        assertThatThrownBy(() -> new SampleRelayService().crash()).isInstanceOf(IllegalStateException.class);
        assertThat(messages()).containsExactly("sample relay start", "sample relay failed");
        ILoggingEvent failed = line("sample relay failed");
        assertThat(failed.getLevel().toString()).isEqualTo("ERROR");
        Map<String, Object> fields = pairs(failed);
        assertThat(fields).containsEntry("error.type", "java.lang.IllegalStateException").containsKey("event.duration");
        assertThat(String.valueOf(fields.get("error.stack_trace"))).doesNotContain("secret body");
        assertThat(failed.getThrowableProxy()).isNull();
    }

    @Test
    void anIdentifiedUserIsOnLaterLinesAndOnTheResultLine() throws Exception {
        UUID user = UUID.randomUUID();
        var filter = new RequestLogFilter(new StaticListableBeanFactory().getBeanProvider(RequestMappingHandlerMapping.class));
        filter.doFilter(new MockHttpServletRequest("GET", "/identify-test"), new MockHttpServletResponse(), (request, response) -> {
            AppLogger.current().info("sample before identify");
            // 세션을 확인한 SessionFilter처럼 흐름 중간에 사용자를 알린다
            AppLogger.current().identify(user);
            AppLogger.current().info("sample after identify");
            // 안쪽 흐름의 바인딩(with)이 닫혀도 바깥 요청의 사용자는 MDC에 남는다
            LogFlow.task("sample inner", inner -> inner.with(LogField.projectId(UUID.randomUUID())));
            assertThat(MDC.get("user.id")).isEqualTo(user.toString());
        });
        assertThat(line("sample before identify").getMDCPropertyMap()).doesNotContainKey("user.id");
        assertThat(line("sample after identify").getMDCPropertyMap()).containsEntry("user.id", user.toString());
        assertThat(line("sample inner completed").getMDCPropertyMap()).containsEntry("user.id", user.toString());
        assertThat(line("request completed").getMDCPropertyMap()).containsEntry("user.id", user.toString());
        // 요청이 끝나면 사용자가 MDC에 남지 않는다(스레드를 다시 쓰는 다음 요청에 붙지 않는다)
        assertThat(MDC.get("user.id")).isNull();
    }

    @Test
    void identifyWithoutABoundFlowDoesNothing() {
        AppLogger.current().identify(UUID.randomUUID());
        assertThat(MDC.get("user.id")).isNull();
    }

    @Test
    void aCallOpensASpanAndWritesItsResultLineInsideIt() throws IOException {
        // 외부 호출 결과 줄의 span.id가 호출을 감싸는 span이다. 계측의 CLIENT span(채팅 SSE의 HTTP, Valkey 명령)은 그 자식이 된다
        // (LOG_SCHEMA.md 7절 "외부 호출")
        String[] inside = new String[1];
        LogFlow.call("upstream", URI.create("http://ai-chat-api:8080/chat/sessions/s-1/messages"), call -> {
            inside[0] = Span.current().getSpanContext().getSpanId();
            return call.status(200);
        });
        SpanData upstream = span("upstream call");
        assertThat(inside[0]).isEqualTo(upstream.getSpanId());
        assertThat(upstream.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(upstream.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(upstream.getInstrumentationScopeInfo().getName()).isEqualTo(TraceContext.INSTRUMENTATION_SCOPE);
        assertThat(spanAtLine.get(line("upstream call completed")).getSpanId()).isEqualTo(upstream.getSpanId());

        // 예외로 끝난 호출: span은 ERROR이고 설명은 예외 종류다. 실패 줄도 그 span 안에서 찍힌다
        assertThatThrownBy(() -> LogFlow.call("valkey", call -> {
            throw new IOException("secret host in message");
        })).isInstanceOf(IOException.class);
        SpanData valkey = span("valkey call");
        assertThat(valkey.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(valkey.getStatus().getDescription()).isEqualTo("java.io.IOException");
        assertThat(spanAtLine.get(line("valkey call failed")).getSpanId()).isEqualTo(valkey.getSpanId());
    }

    @Test
    void aCallThatFailsWithoutAnExceptionOrAServerErrorMarksItsSpan() {
        // 예외 없이 알린 실패(fail)와 상대의 5xx도 span 상태가 ERROR다. 설명은 결과 줄의 error.type과 같은 값이다
        LogFlow.call("valkey", call -> call.fail("DEADLINE_EXCEEDED"));
        new SampleStreamClient().open(503);
        assertThat(span("valkey call").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span("valkey call").getStatus().getDescription()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(span("upstream call").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span("upstream call").getStatus().getDescription()).isEqualTo("UPSTREAM_SERVER_ERROR");
    }

    @Test
    void aCallSpanIsAChildOfTheCurrentSpan() {
        // 요청 안의 호출은 요청 서버 span(agent가 연 지금 span)의 자식이다
        Span request = OTEL.getOpenTelemetry().getTracer("test").spanBuilder("GET").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = request.makeCurrent()) {
            new SampleStreamClient().open(200);
        } finally {
            request.end();
        }
        SpanData upstream = span("upstream call");
        assertThat(upstream.getTraceId()).isEqualTo(request.getSpanContext().getTraceId());
        assertThat(upstream.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
    }

    @Test
    void aTaskOpensItsOwnSpanAndItsLinesAreInsideIt() {
        new SampleRelayService().relay(UUID.randomUUID());
        SpanData relay = span("sample relay");
        assertThat(spanAtLine.get(line("sample relay start")).getSpanId()).isEqualTo(relay.getSpanId());
        assertThat(spanAtLine.get(line("sample chunk relayed")).getSpanId()).isEqualTo(relay.getSpanId());
        assertThat(spanAtLine.get(line("sample relay completed")).getSpanId()).isEqualTo(relay.getSpanId());
        // 흐름 밖에서 시작한 작업(기동 때 예열)이면 이 span이 root다
        assertThat(relay.getParentSpanContext().isValid()).isFalse();
        assertThat(relay.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);

        // 실패한 작업: 예외면 예외 종류, fail이면 그 이유가 span 상태의 설명이다
        assertThatThrownBy(() -> new SampleRelayService().crash()).isInstanceOf(IllegalStateException.class);
        LogFlow.task("sample interrupted relay", interrupted -> interrupted.fail("AI_CHAT_STREAM_INTERRUPTED"));
        assertThat(OTEL.getSpans()).filteredOn(s -> s.getName().equals("sample relay"))
                .anySatisfy(s -> assertThat(s.getStatus().getDescription()).isEqualTo("java.lang.IllegalStateException"));
        assertThat(span("sample interrupted relay").getStatus().getDescription()).isEqualTo("AI_CHAT_STREAM_INTERRUPTED");
    }

    /** 이 테스트에서 끝난 span 가운데 그 이름의 첫 것. */
    private static SpanData span(String name) {
        return OTEL.getSpans().stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name));
    }
}
