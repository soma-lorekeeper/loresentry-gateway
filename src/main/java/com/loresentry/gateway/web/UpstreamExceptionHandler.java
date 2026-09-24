package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.UpstreamException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UpstreamExceptionHandler {

    /** Diagnostic probe failures use the legacy health error contract. */
    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamFailure(UpstreamException exception) {
        return ErrorResponses.response(GatewayFailure.Reason.UPSTREAM_UNAVAILABLE, exception.getUpstream());
    }

    @ExceptionHandler(GatewayFailure.class)
    public ResponseEntity<Map<String, Object>> handleGatewayFailure(GatewayFailure failure) {
        return ErrorResponses.response(failure.reason(), null);
    }
}
