package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.UpstreamException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UpstreamExceptionHandler {

    /**
     * 업스트림에 **닿지 못한** 경우다. 업스트림이 오류 응답을 준 경우는 여기 오지 않고
     * 본문 그대로 클라이언트에게 통과한다 — 이유를 아는 쪽이 업스트림이다.
     */
    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamFailure(UpstreamException exception) {
        return ErrorResponses.response(GatewayFailure.Reason.UPSTREAM_UNAVAILABLE, exception.getUpstream());
    }

    @ExceptionHandler(GatewayFailure.class)
    public ResponseEntity<Map<String, Object>> handleGatewayFailure(GatewayFailure failure) {
        return ErrorResponses.response(failure.reason(), null);
    }
}
