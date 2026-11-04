package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Supplier;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * AppLogger가 줄마다 찍은 위치(log.layer·log.package·log.origin.function)와 문맥 태그를 넣는지 확인한다(LOG_SCHEMA.md 5절).
 * 레이어는 클래스 이름 끝 규칙, @LogLayer, 바깥 클래스 순서로 정한다.
 */
class AppLoggerLocationTest {
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach void attach() { events.start(); root.addAppender(events); }
    @AfterEach void detach() { root.detachAppender(events); }

    private Map<String, Object> pairs() {
        ILoggingEvent last = events.list.getLast();
        Map<String, Object> out = new java.util.HashMap<>();
        last.getKeyValuePairs().forEach(p -> out.put(p.key, p.value));
        return out;
    }

    static final class SampleController { void get() { AppLogger.current().info("sample get requested"); } }
    static final class SampleService { void get() { AppLogger.current().info("sample get"); } }
    static final class SampleApiClient { void get() { AppLogger.current().info("sample get call"); } }
    @LogLayer("security") static final class SampleVerifier { void verify() { AppLogger.current().info("sample verified"); } }
    static final class Helper { void run() { AppLogger.current().info("helper"); } }
    static final class SampleRepository {
        // 람다·익명 클래스 안에서 찍어도 바깥 클래스의 레이어가 나와야 한다
        void read() { Supplier<String> read = () -> { AppLogger.current().info("sample read"); return "x"; }; read.get(); }
        // 람다 안의 람다도 감싼 메서드 이름(stream)이 나와야 한다
        void stream() { Runnable outer = () -> { Runnable inner = () -> AppLogger.current().info("sample stream"); inner.run(); }; outer.run(); }
    }

    @Test
    void layerComesFromTheClassNameSuffix() {
        new SampleController().get();
        assertThat(pairs()).containsEntry("log.layer", "controller").containsEntry("log.origin.function", "get")
                .containsEntry("log.package", "com.loresentry.gateway.logging");
        new SampleService().get();
        assertThat(pairs()).containsEntry("log.layer", "service");
        new SampleApiClient().get();
        assertThat(pairs()).containsEntry("log.layer", "client");
        new SampleRepository().read();
        assertThat(pairs()).containsEntry("log.layer", "repository");
    }

    @Test
    void lambdasReportTheEnclosingMethod() {
        // javac가 만든 lambda$read$0이 아니라 람다를 감싼 메서드 이름이 log.origin.function에 들어간다
        new SampleRepository().read();
        assertThat(pairs()).containsEntry("log.origin.function", "read").containsEntry("log.layer", "repository");
        new SampleRepository().stream();
        assertThat(pairs()).containsEntry("log.origin.function", "stream");
        assertThat(events.list.getLast().getLoggerName()).endsWith("AppLoggerLocationTest$SampleRepository");
    }

    @Test
    void annotationWinsAndUnknownClassesAreOther() {
        new SampleVerifier().verify();
        assertThat(pairs()).containsEntry("log.layer", "security");
        // Helper는 이름 끝 규칙에 맞지 않지만 바깥 클래스(AppLoggerLocationTest)도 맞지 않아 other가 된다
        new Helper().run();
        assertThat(pairs()).containsEntry("log.layer", "other");
    }

    @Test
    void contextTagsBecomeMarkers() {
        try (var bound = AppLogger.bind(new AppLogger("a".repeat(32), "b".repeat(16), null).withTag("health"))) {
            new SampleService().get();
        }
        assertThat(events.list.getLast().getMarkerList()).extracting(org.slf4j.Marker::getName).containsExactly("health");
    }

    @Test
    void contextFieldsStayInMdcWhileBoundSoFrameworkLinesGetThem() {
        // 바인딩한 동안 같은 스레드에서 프레임워크가 찍는 줄(여기서는 SLF4J logger를 직접 쓴 줄)의 MDC에 요청의 문맥 필드가 있다
        org.slf4j.Logger framework = LoggerFactory.getLogger("org.springframework.sample.Framework");
        AppLogger request = new AppLogger("a".repeat(32), "b".repeat(16), null).with(LogField.requestId("Root=1-abc"),
                LogField.urlPath("/projects/p-1"), LogField.route("/projects/{projectId}"), LogField.httpMethod("GET"),
                LogField.projectId(java.util.UUID.randomUUID()));
        try (var bound = AppLogger.bind(request)) {
            framework.warn("framework line");
            assertThat(events.list.getLast().getMDCPropertyMap()).containsEntry("url.path", "/projects/p-1")
                    .containsEntry("http.route", "/projects/{projectId}").containsEntry("http.request.method", "GET")
                    .containsEntry("http.request.id", "Root=1-abc").containsEntry("trace.id", "a".repeat(32))
                    // MDC_CONTEXT_KEYS 밖의 문맥 필드는 MDC에 넣지 않는다(우리 줄에만 붙는다)
                    .doesNotContainKey("loresentry.project_id");
            // 안쪽에서 문맥 필드가 없는 객체를 바인딩하면 그동안은 요청 값이 MDC에 남지 않는다
            try (var inner = AppLogger.bind(new AppLogger("c".repeat(32), "d".repeat(16), null))) {
                framework.warn("inner line");
                assertThat(events.list.getLast().getMDCPropertyMap()).doesNotContainKeys("url.path", "http.route", "http.request.method");
            }
            framework.warn("after inner");
            assertThat(events.list.getLast().getMDCPropertyMap()).containsEntry("url.path", "/projects/p-1");
        }
        // 바인딩이 끝나면 MDC에서 빠진다(스레드를 다시 쓰는 다음 요청에 남지 않는다)
        framework.warn("after request");
        assertThat(events.list.getLast().getMDCPropertyMap()).doesNotContainKeys("url.path", "http.route", "http.request.method",
                "http.request.id", "trace.id");
    }
}
