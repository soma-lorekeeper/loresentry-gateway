package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.loresentry.gateway.LocalTestEnvironment;
import com.loresentry.gateway.client.session.OpaqueSessionReader.VerifiedSession;
import com.loresentry.gateway.security.OpaqueSessionVerifier;
import com.loresentry.gateway.security.SecurityFailure;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 필터 순서(숨김 → observation → RequestLogFilter → CSRF → 세션)로 요청 하나를 보내 확인한다.
 * <ul>
 *   <li>인터넷에서 온 traceparent는 무시하고 새 trace를 시작한다</li>
 *   <li>응답 헤더 X-Trace-Id와 에러 본문 trace_id가 그 trace ID다</li>
 *   <li>접근 로그가 ECS JSON 한 줄로, 같은 trace ID와 응답 상태를 담아 남는다</li>
 *   <li>실패 이유(보안 응답이 맡긴 error.type)는 따로 찍히지 않고 결과 줄(request completed)에 붙는다</li>
 *   <li>브라우저가 부른 경로(url.path)와 경로 템플릿(http.route)이 요청의 모든 줄에 붙는다</li>
 *   <li>우리 예외 처리기가 다루지 않은 프레임워크 예외도 그 클래스가 결과 줄의 error.type이 된다</li>
 *   <li>비동기 요청의 오류·시간 초과는 원인 줄 하나와 결과 줄 하나로 남는다(결과 줄이 두 번 찍히지 않는다)</li>
 * </ul>
 */
@LocalTestEnvironment
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EXTERNAL_TRACE = "0af7651916cd43dd8448eb211c80319c";

    @Autowired MockMvc mvc;
    @Autowired Tracer tracer;
    @MockitoBean OpaqueSessionVerifier verifier;

    private static List<JsonNode> lines(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.startsWith("{")).map(JSON::readTree).toList();
    }

    @Test
    void startsANewTraceAndLogsTheRequestWithIt(CapturedOutput output) throws Exception {
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
        var response = mvc.perform(get("/chat/usage").queryParam("q", "secret-query")
                        .header("traceparent", "00-" + EXTERNAL_TRACE + "-b7ad6b7169203331-01")
                        .header("X-Amzn-Trace-Id", "Root=1-6731b2f0-0123456789abcdef01234567"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();

        String traceId = response.getHeader(RequestLogFilter.TRACE_HEADER);
        assertThat(traceId).hasSize(32).isNotEqualTo(EXTERNAL_TRACE);
        assertThat(JSON.readTree(response.getContentAsString()).get("trace_id").asString()).isEqualTo(traceId);

        var mine = lines(output).stream().filter(json -> traceId.equals(json.at("/trace/id").asString())).toList();
        assertThat(mine).extracting(json -> json.get("message").asString()).containsExactly("request received", "request completed");
        JsonNode completed = mine.stream().filter(json -> "request completed".equals(json.get("message").asString())).findFirst().orElseThrow();
        assertThat(completed.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(completed.at("/http/response/status_code").asLong()).isEqualTo(401L);
        assertThat(completed.at("/error/type").asString()).isEqualTo("SESSION_REQUIRED");
        // 세션 필터에서 막혀 컨트롤러까지 가지 않았어도, 경로 템플릿은 요청 시작 때 찾아 모든 줄에 붙는다
        assertThat(mine).allSatisfy(json -> {
            assertThat(json.at("/http/route").asString()).isEqualTo("/chat/usage");
            assertThat(json.at("/url/path").asString()).isEqualTo("/chat/usage");
        });
        assertThat(completed.has("tags")).isFalse();
        assertThat(completed.at("/url/path").asString()).isEqualTo("/chat/usage");
        assertThat(completed.at("/http/request/id").asString()).isEqualTo("Root=1-6731b2f0-0123456789abcdef01234567");
        assertThat(completed.at("/service/name").asString()).isEqualTo("gateway-api");
        assertThat(completed.at("/ecs/version").asString()).isEqualTo("8.11");
        assertThat(completed.at("/log/logger").asString()).isEqualTo(RequestLogFilter.class.getName());
        assertThat(completed.at("/event/duration").asLong()).isPositive();
        // 외부 trace ID와 쿼리스트링은 어떤 줄에도 남지 않는다
        assertThat(output.getOut()).doesNotContain(EXTERNAL_TRACE).doesNotContain("secret-query");
    }

    @Test
    void healthChecksAreTracedAndEveryLineIsTaggedHealth(CapturedOutput output) throws Exception {
        // 헬스체크도 다른 요청처럼 span·로그를 남긴다. Logstash가 tags: health인 info 줄을 버린다(LOG_SCHEMA.md 4절)
        var response = mvc.perform(get("/health")).andExpect(status().isOk()).andReturn().getResponse();
        String traceId = response.getHeader(RequestLogFilter.TRACE_HEADER);
        assertThat(traceId).hasSize(32);
        var health = lines(output).stream().filter(json -> traceId.equals(json.at("/trace/id").asString())).toList();
        assertThat(health).extracting(json -> json.get("message").asString()).contains("request received", "request completed");
        assertThat(health).allSatisfy(json -> assertThat(json.get("tags").toString()).contains("health"));
        JsonNode completed = health.stream().filter(json -> "request completed".equals(json.get("message").asString())).findFirst().orElseThrow();
        // 헬스체크 흐름의 모든 줄(진입, service, 내부 호출, 결과)에 경로와 경로 템플릿이 붙는다
        assertThat(health).allSatisfy(json -> {
            assertThat(json.at("/http/route").asString()).isEqualTo("/health");
            assertThat(json.at("/url/path").asString()).isEqualTo("/health");
        });
        assertThat(completed.at("/log/layer").asString()).isEqualTo("filter");
        assertThat(completed.at("/log/package").asString()).isEqualTo("com.loresentry.gateway.logging");
    }

    @Test
    void pathsWithoutAControllerHaveNoRouteButKeepThePath(CapturedOutput output) throws Exception {
        // 맞는 컨트롤러가 없는 경로는 경로 템플릿이 없다. 실제 경로는 남는다
        var response = mvc.perform(get("/no-such-path")).andReturn().getResponse();
        String traceId = response.getHeader(RequestLogFilter.TRACE_HEADER);
        var mine = lines(output).stream().filter(json -> traceId.equals(json.at("/trace/id").asString())).toList();
        assertThat(mine).isNotEmpty().allSatisfy(json -> {
            assertThat(json.at("/http/route").isMissingNode()).isTrue();
            assertThat(json.at("/url/path").asString()).isEqualTo("/no-such-path");
        });
    }

    @Test
    void frameworkExceptionsHandTheirClassToTheResultLine(CapturedOutput output) throws Exception {
        // 세션은 통과하고 맞는 경로가 없는 요청. 우리 예외 처리기는 이 예외를 다루지 않으므로 Spring 기본 resolver가 404를 쓰고,
        // 그 앞의 resolver가 예외 클래스를 결과 줄에 맡긴다
        String session = "A".repeat(43);
        when(verifier.verify(session)).thenReturn(new VerifiedSession(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(14))));
        var response = mvc.perform(get("/no-such-path").cookie(new Cookie("ls_session", session)))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        String traceId = response.getHeader(RequestLogFilter.TRACE_HEADER);
        var completed = lines(output).stream().filter(json -> traceId.equals(json.at("/trace/id").asString()))
                .filter(json -> "request completed".equals(json.get("message").asString())).toList();
        assertThat(completed).singleElement().satisfies(json -> {
            assertThat(json.at("/http/response/status_code").asLong()).isEqualTo(404L);
            assertThat(json.at("/error/type").asString()).isEqualTo("org.springframework.web.servlet.resource.NoResourceFoundException");
        });
    }

    @Test
    void asyncErrorLeavesACauseLineAndOneResultLineWithItsClass(CapturedOutput output) throws Exception {
        var mine = async(output, (listener, context) -> listener.onError(new AsyncEvent(context, new IOException("private-detail"))));
        assertThat(mine).extracting(json -> json.get("message").asString())
                .containsExactly("request received", "async request failed", "request completed");
        assertThat(mine.get(1).at("/log/level").asString()).isEqualTo("WARN");
        assertThat(mine.get(1).at("/error/type").asString()).isEqualTo("java.io.IOException");
        assertThat(mine.get(2).at("/error/type").asString()).isEqualTo("java.io.IOException");
        // 예외 메시지는 어떤 줄에도 남지 않는다
        assertThat(output.getOut()).doesNotContain("private-detail");
    }

    @Test
    void browserDisconnectDuringAnAsyncRequestIsInfo(CapturedOutput output) throws Exception {
        var mine = async(output, (listener, context) ->
                listener.onError(new AsyncEvent(context, new AsyncRequestNotUsableException("private-detail"))));
        assertThat(mine).extracting(json -> json.get("message").asString())
                .containsExactly("request received", "async request client disconnected", "request completed");
        assertThat(mine.get(1).at("/log/level").asString()).isEqualTo("INFO");
        assertThat(mine.get(2).at("/error/type").asString()).isEqualTo(AsyncRequestNotUsableException.class.getName());
    }

    @Test
    void asyncTimeoutHandsItsReasonToTheResultLine(CapturedOutput output) throws Exception {
        var mine = async(output, (listener, context) -> listener.onTimeout(new AsyncEvent(context)));
        assertThat(mine).extracting(json -> json.get("message").asString())
                .containsExactly("request received", "async request timed out", "request completed");
        assertThat(mine.get(2).at("/error/type").asString()).isEqualTo("ASYNC_REQUEST_TIMEOUT");
    }

    /** 비동기 콜백 하나를 부르는 테스트 동작. 서블릿 컨테이너처럼 그 뒤에 onComplete가 온다. */
    private interface AsyncCallback {
        void call(AsyncListener listener, MockAsyncContext context) throws IOException;
    }

    /**
     * 비동기로 시작한 요청 하나를 RequestLogFilter에 통과시키고, 컨테이너 대신 콜백(오류·시간 초과)과 onComplete를 부른다.
     * 그 요청의 줄(고유한 url.path로 고른다)을 순서대로 돌려준다. 경로 템플릿 매핑은 쓰지 않는다.
     */
    private List<JsonNode> async(CapturedOutput output, AsyncCallback callback) throws Exception {
        String path = "/async-test/" + UUID.randomUUID();
        var request = new MockHttpServletRequest("POST", path);
        request.setAsyncSupported(true);
        var filter = new RequestLogFilter(tracer, new StaticListableBeanFactory().getBeanProvider(RequestMappingHandlerMapping.class));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.startAsync());
        var context = (MockAsyncContext) request.getAsyncContext();
        for (AsyncListener listener : context.getListeners()) callback.call(listener, context);
        context.complete();
        return lines(output).stream().filter(json -> path.equals(json.at("/url/path").asString())).toList();
    }
}
