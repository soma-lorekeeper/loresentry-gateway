package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.HashMap;
import java.util.Map;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loresentry.gateway.logging.LoggingConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 내부 서비스 호출 결과 줄(upstream call completed)의 위치와 주소 필드를 확인한다(LOG_SCHEMA.md 5절).
 * 우리 client 코드가 RestClient를 부르는 상황이어야 해서 logging 패키지 밖(client 패키지)에 둔다.
 * <ul>
 *   <li>log.logger·log.origin.function은 실제로 HTTP를 보낸 Spring RestClient 메서드다(호출한 우리 메서드는 앞 진입 줄에 있다)</li>
 *   <li>log.layer는 client다</li>
 *   <li>내부 서비스 주소는 url.full이고 쿼리는 빠진다. url.path는 브라우저 요청의 값이라 여기서는 없다</li>
 * </ul>
 */
class UpstreamCallLogLocationTest {
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
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
}
