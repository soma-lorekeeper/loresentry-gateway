package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** EcsLogFormatter가 LOG_SCHEMA.md 5절의 중첩 ECS JSON을 한 줄로 만드는지 확인한다. */
class EcsLogFormatterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final LoggerContext context = new LoggerContext();
    private final EcsLogFormatter formatter = new EcsLogFormatter(new MockEnvironment()
            .withProperty("logging.structured.ecs.service.name", "gateway-api")
            .withProperty("logging.structured.ecs.service.version", "build-1-1"));

    private LoggingEvent event(Level level, String message, Throwable cause) {
        return new LoggingEvent("test", context.getLogger("com.loresentry.gateway.Sample"), level, message, cause, null);
    }

    private JsonNode format(LoggingEvent event) {
        String line = formatter.format(event);
        assertThat(line).endsWith("\n").doesNotContain("\n\t").hasLineCount(1);
        return JSON.readTree(line);
    }

    @Test
    void writesRequiredFieldsAsNestedObjects() {
        JsonNode json = format(event(Level.INFO, "request received", null));
        assertThat(json.get("@timestamp").asString()).isNotBlank();
        assertThat(json.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(json.at("/log/logger").asString()).isEqualTo("com.loresentry.gateway.Sample");
        assertThat(json.at("/service/name").asString()).isEqualTo("gateway-api");
        assertThat(json.at("/service/version").asString()).isEqualTo("build-1-1");
        assertThat(json.at("/ecs/version").asString()).isEqualTo("8.11");
        assertThat(json.get("message").asString()).isEqualTo("request received");
        // 점이 든 키({"log.level":..})는 Logstash가 필드로 읽지 못한다
        assertThat(json.has("log.level")).isFalse();
    }

    @Test
    void aLoggerKeyValueReplacesTheLoggerName() {
        // AppLogger가 줄 위치를 따로 넘긴 경우(RestClient 호출 결과 줄): SLF4J logger 이름 대신 그 값이 log.logger다
        var event = event(Level.INFO, "upstream call completed", null);
        event.addKeyValuePair(new KeyValuePair("log.logger", "org.springframework.web.client.DefaultRestClient$DefaultResponseSpec"));
        JsonNode json = format(event);
        assertThat(json.at("/log/logger").asString()).isEqualTo("org.springframework.web.client.DefaultRestClient$DefaultResponseSpec");
    }

    @Test
    void mergesKeyValuesIntoTheSameObjectsTheFormatterWrites() {
        var event = event(Level.WARN, "upstream call completed", null);
        event.addKeyValuePair(new KeyValuePair("log.origin.function", "intercept"));
        event.addKeyValuePair(new KeyValuePair("loresentry.project_id", "p-1"));
        event.addKeyValuePair(new KeyValuePair("http.response.status_code", 503L));
        JsonNode json = format(event);
        assertThat(json.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(json.at("/log/origin/function").asString()).isEqualTo("intercept");
        assertThat(json.at("/loresentry/project_id").asString()).isEqualTo("p-1");
        assertThat(json.at("/http/response/status_code").asLong()).isEqualTo(503L);
    }

    @Test
    void prefersAppLoggerTraceAndFallsBackToTheCurrentSpan() {
        var bound = event(Level.INFO, "a", null);
        bound.setMDCPropertyMap(Map.of("trace.id", "t-app", "span.id", "s-app", "traceId", "t-otel", "spanId", "s-otel",
                "user.id", "u-1", "other", "not exported"));
        JsonNode json = format(bound);
        assertThat(json.at("/trace/id").asString()).isEqualTo("t-app");
        assertThat(json.at("/span/id").asString()).isEqualTo("s-app");
        assertThat(json.at("/user/id").asString()).isEqualTo("u-1");
        assertThat(json.has("other")).isFalse();
        assertThat(json.has("traceId")).isFalse();

        var framework = event(Level.INFO, "b", null);
        framework.setMDCPropertyMap(Map.of("traceId", "t-otel", "spanId", "s-otel"));
        JsonNode fallback = format(framework);
        assertThat(fallback.at("/trace/id").asString()).isEqualTo("t-otel");
        assertThat(fallback.at("/span/id").asString()).isEqualTo("s-otel");
    }

    @Test
    void writesContextFieldsFromMdcOnFrameworkLines() {
        // 프레임워크 줄: key-value가 없어도 AppLogger가 바인딩하며 MDC에 넣은 요청의 문맥 필드가 붙는다
        var framework = event(Level.WARN, "framework line", null);
        framework.setMDCPropertyMap(Map.of("url.path", "/projects/p-1", "http.route", "/projects/{projectId}",
                "http.request.method", "GET", "http.request.id", "Root=1-abc", "loresentry.project_id", "not a context key"));
        JsonNode json = format(framework);
        assertThat(json.at("/url/path").asString()).isEqualTo("/projects/p-1");
        assertThat(json.at("/http/route").asString()).isEqualTo("/projects/{projectId}");
        assertThat(json.at("/http/request/method").asString()).isEqualTo("GET");
        assertThat(json.at("/http/request/id").asString()).isEqualTo("Root=1-abc");
        // 목록 밖의 MDC 값은 내보내지 않는다
        assertThat(json.has("loresentry")).isFalse();
    }

    @Test
    void lineKeyValuesWinOverMdcContextFields() {
        // AppLogger 줄은 문맥 필드를 key-value로도 넘긴다. 둘이 다르면 그 줄에 넘긴 값이 남는다
        var line = event(Level.INFO, "d", null);
        line.addKeyValuePair(new KeyValuePair("url.path", "/from-line"));
        line.setMDCPropertyMap(Map.of("url.path", "/from-mdc", "http.route", "/from-mdc-route"));
        JsonNode json = format(line);
        assertThat(json.at("/url/path").asString()).isEqualTo("/from-line");
        assertThat(json.at("/http/route").asString()).isEqualTo("/from-mdc-route");
    }

    @Test
    void writesTheExceptionAsErrorFieldsOnOneLine() {
        JsonNode json = format(event(Level.ERROR, "request failed", new IllegalStateException("boom")));
        assertThat(json.at("/error/type").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(json.at("/error/message").asString()).isEqualTo("boom");
        assertThat(json.at("/error/stack_trace").asString()).contains("IllegalStateException").contains("\tat ");
    }

    @Test
    void keepsTheFirstValueWhenANameConflicts() {
        var event = event(Level.INFO, "c", null);
        event.addKeyValuePair(new KeyValuePair("log.level", "should not replace the object"));
        event.addKeyValuePair(new KeyValuePair("log", "should not replace the object either"));
        JsonNode json = format(event);
        assertThat(json.at("/log/level").asString()).isEqualTo("INFO");
    }
}
