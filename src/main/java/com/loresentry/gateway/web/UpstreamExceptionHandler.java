package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.UpstreamException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UpstreamExceptionHandler {

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<Map<String, String>> handleUpstreamFailure(UpstreamException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error", "upstream_unavailable",
                        "upstream", exception.getUpstream()));
    }
}
