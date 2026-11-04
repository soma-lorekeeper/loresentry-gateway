package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * </ul>
 */
class LogFlowTest {
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
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
        var filter = new RequestLogFilter(mock(Tracer.class), new StaticListableBeanFactory().getBeanProvider(RequestMappingHandlerMapping.class));
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
}
