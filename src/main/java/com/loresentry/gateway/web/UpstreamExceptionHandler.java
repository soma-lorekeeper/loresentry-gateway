package com.loresentry.gateway.web;

import java.util.Map;
import java.util.Set;

import com.loresentry.gateway.client.UpstreamException;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 컨트롤러에 걸리는 기본 실패 처리. 진단 probe의 upstream 실패, 요청 경계 실패(GatewayFailure), 세션 실패를 다룬다.
 *
 * <p>오류 본문의 trace_id는 {@link ErrorResponses}·SecurityResponses가 싣는다. 실패 이유(우리 오류 code)는 결과 줄(request completed)에
 * 맡긴다. 세션 실패는 SecurityResponses.write가 맡기므로 여기서 또 맡기지 않는다.
 */
@RestControllerAdvice
public class UpstreamExceptionHandler {
    /** 내부 서비스 이름 허용 목록. client/의 UpstreamClient 구현이 넘기는 이름이다(ProbeService와 같다). */
    private static final Set<String> UPSTREAMS = Set.of("authentication", "content", "ai-chat", "graph-rag");

    /** Diagnostic probe failures use the legacy health error contract. */
    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamFailure(UpstreamException exception) {
        // 예외 메시지에는 호출 URI가 들어 있어 남기지 않는다. 호출 결과는 upstream 호출 로그에 이미 있다.
        // 결과 줄에는 어느 대상의 실패인지도 붙인다. error.type은 우리 오류 code 하나라 원인 예외 클래스는 싣지 않는다
        failed(GatewayFailure.Reason.UPSTREAM_UNAVAILABLE);
        AppLogger.current().addToOutcome(LogField.code("upstream", exception.getUpstream(), UPSTREAMS));
        return ErrorResponses.response(GatewayFailure.Reason.UPSTREAM_UNAVAILABLE, exception.getUpstream());
    }

    @ExceptionHandler(GatewayFailure.class)
    public ResponseEntity<Map<String, Object>> handleGatewayFailure(GatewayFailure failure) {
        failed(failure.reason());
        return ErrorResponses.response(failure.reason(), null);
    }
    @ExceptionHandler(com.loresentry.gateway.security.SecurityFailure.class)
    public void security(com.loresentry.gateway.security.SecurityFailure failure,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        com.loresentry.gateway.security.SecurityResponses.write(response,failure.reason());
    }

    /** 처리한 실패의 이유를 결과 줄에 맡긴다. 상태·레벨은 결과 줄이 응답에서 정한다. */
    private static void failed(GatewayFailure.Reason reason) {
        AppLogger.current().addToOutcome(LogField.errorType(reason.name()));
    }
}
