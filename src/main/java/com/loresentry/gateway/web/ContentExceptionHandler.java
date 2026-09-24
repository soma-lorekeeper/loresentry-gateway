package com.loresentry.gateway.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.client.content.ContentCallFailure;
import com.loresentry.gateway.web.content.ContentDtos;
import com.loresentry.gateway.web.content.ContentApiController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ContentApiController.class)
public class ContentExceptionHandler {
    record Error(String code, String message, @JsonProperty("next_action") String nextAction) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Conflict(String code, String message, @JsonProperty("next_action") String nextAction,
                    ContentDtos.Content current, ContentDtos.Snapshot base) {}
    @ExceptionHandler(ContentCallFailure.class)
    ResponseEntity<?> content(ContentCallFailure failure) {
        if ("DOCUMENT_CONFLICT".equals(failure.code()))
            return ResponseEntity.status(409).header("Cache-Control","no-store").body(new Conflict(
                    failure.code(), "The document changed. Resolve the conflict.", "NONE",
                    ContentDtos.Content.from(failure.current()), ContentDtos.Snapshot.from(failure.base())));
        return error(failure.status(), failure.code(), failure.status() == 503 ? "RETRY_LATER" : "NONE");
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalid() { return error(400, "INVALID_REQUEST", "NONE"); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> path() { return error(404, "PROJECT_NOT_FOUND", "NONE"); }
    private ResponseEntity<Error> error(int status, String code, String action) {
        String message = switch (status) {
            case 400 -> "Invalid request.";
            case 404 -> "The requested resource was not found.";
            case 409 -> "The operation conflicts with the current state.";
            case 503 -> "Content service is unavailable.";
            case 502 -> "The service returned an invalid response.";
            default -> "An internal error occurred.";
        };
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new Error(code,message,action));
    }
}
