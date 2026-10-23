package com.loresentry.gateway.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    // 최신화 오류는 화면이 다음 행동을 고를 상세 값을 하나씩 더 싣는다. 키 이름은 Content 와 같다.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record DraftConflict(String code, String message, @JsonProperty("next_action") String nextAction,
                         ContentDtos.DraftConflict current) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record UnmatchedDrafts(String code, String message, @JsonProperty("next_action") String nextAction, List<UUID> drafts) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ChangedDocuments(String code, String message, @JsonProperty("next_action") String nextAction,
                            List<ContentDtos.ChangedDocument> documents) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record InvalidDrafts(String code, String message, @JsonProperty("next_action") String nextAction,
                         List<Map<String, String>> problems) {}
    @ExceptionHandler(ContentCallFailure.class)
    ResponseEntity<?> content(ContentCallFailure failure) {
        if ("DOCUMENT_CONFLICT".equals(failure.code()))
            return ResponseEntity.status(409).header("Cache-Control","no-store").body(new Conflict(
                    failure.code(), "The document changed. Resolve the conflict.", "NONE",
                    ContentDtos.Content.from(failure.current()), ContentDtos.Snapshot.from(failure.base())));
        // 장애·횟수 제한과 이미 진행 중인 최신화(409)는 잠시 뒤 다시 하면 된다.
        String action = failure.status() == 503 || failure.status() == 429 || "REFRESH_IN_PROGRESS".equals(failure.code())
                ? "RETRY_LATER" : "NONE";
        var details = failure.details();
        if (details == null) return error(failure.status(), failure.code(), action);
        String code = failure.code(), message = message(failure.status());
        Object body = switch (code) {
            case "REFRESH_DRAFT_CONFLICT" -> new DraftConflict(code, message, action, ContentDtos.DraftConflict.from(details.current()));
            case "REFRESH_DRAFTS_UNMATCHED" -> new UnmatchedDrafts(code, message, action, details.drafts());
            case "REFRESH_DOCUMENTS_CHANGED" -> new ChangedDocuments(code, message, action,
                    details.documents().stream().map(ContentDtos.ChangedDocument::from).toList());
            case "REFRESH_DRAFTS_INVALID" -> new InvalidDrafts(code, message, action, details.problems());
            default -> new Error(code, message, action);
        };
        return ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(body);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalid() { return error(400, "INVALID_REQUEST", "NONE"); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> path() { return error(404, "PROJECT_NOT_FOUND", "NONE"); }
    private ResponseEntity<Error> error(int status, String code, String action) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new Error(code,message(status),action));
    }
    private static String message(int status) {
        return switch (status) {
            case 400 -> "Invalid request.";
            case 404 -> "The requested resource was not found.";
            case 409 -> "The operation conflicts with the current state.";
            case 429 -> "Too many requests. Try again later.";
            case 503 -> "Content service is unavailable.";
            case 502 -> "The service returned an invalid response.";
            default -> "An internal error occurred.";
        };
    }
}
