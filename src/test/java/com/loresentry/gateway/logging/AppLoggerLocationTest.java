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
}
