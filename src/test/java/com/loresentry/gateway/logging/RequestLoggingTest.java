package com.loresentry.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import com.loresentry.gateway.LocalTestEnvironment;
import com.loresentry.gateway.security.OpaqueSessionVerifier;
import com.loresentry.gateway.security.SecurityFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 필터 순서(숨김 → observation → RequestLogFilter → CSRF → 세션)로 요청 하나를 보내 확인한다.
 * <ul>
 *   <li>인터넷에서 온 traceparent는 무시하고 새 trace를 시작한다</li>
 *   <li>응답 헤더 X-Trace-Id와 에러 본문 trace_id가 그 trace ID다</li>
 *   <li>접근 로그가 ECS JSON 한 줄로, 같은 trace ID와 응답 상태를 담아 남는다</li>
 *   <li>실패 이유(보안 응답이 맡긴 error.type)는 따로 찍히지 않고 결과 줄(request completed)에 붙는다</li>
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
    void healthChecksHaveNoSpanAndNoReceivedLine(CapturedOutput output) throws Exception {
        var response = mvc.perform(get("/health")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader(RequestLogFilter.TRACE_HEADER)).isNull();
        var health = lines(output).stream().filter(json -> "/health".equals(json.at("/url/path").asString())).toList();
        assertThat(health).extracting(json -> json.get("message").asString()).containsExactly("request completed");
        assertThat(health.getFirst().has("trace")).isFalse();
    }
}
