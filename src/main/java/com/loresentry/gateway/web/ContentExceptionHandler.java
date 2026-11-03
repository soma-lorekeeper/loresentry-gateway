package com.loresentry.gateway.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.client.content.ContentCallFailure;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.web.content.ContentDtos;
import com.loresentry.gateway.web.content.ContentApiController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * content API 요청의 실패를 브라우저용 오류 본문으로 바꾼다.
 *
 * <p>실패마다 실패 이유를 결과 줄(request completed)에 맡기고, 모든 오류 본문의 마지막 칸에 trace_id를 싣는다.
 * trace_id는 문의 받은 값을 Kibana에서 바로 찾는 trace ID다. 추적이 없으면(trace 배선이 없는 테스트) null이라 본문에서 빠진다.
 */
@RestControllerAdvice(assignableTypes = ContentApiController.class)
public class ContentExceptionHandler {
    record Error(String code, String message, @JsonProperty("next_action") String nextAction,
                 @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Conflict(String code, String message, @JsonProperty("next_action") String nextAction,
                    ContentDtos.Content current, ContentDtos.Snapshot base, @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}
    // 최신화 오류는 화면이 다음 행동을 고를 상세 값을 하나씩 더 싣는다. 키 이름은 Content 와 같다.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record DraftConflict(String code, String message, @JsonProperty("next_action") String nextAction,
                         ContentDtos.DraftConflict current, @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record UnmatchedDrafts(String code, String message, @JsonProperty("next_action") String nextAction, List<UUID> drafts,
                           @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ChangedDocuments(String code, String message, @JsonProperty("next_action") String nextAction,
                            List<ContentDtos.ChangedDocument> documents, @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record InvalidDrafts(String code, String message, @JsonProperty("next_action") String nextAction,
                         List<Map<String, String>> problems, @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}
    @ExceptionHandler(ContentCallFailure.class)
    ResponseEntity<?> content(ContentCallFailure failure) {
        // 잘못된 응답·연결 실패는 gateway가 붙인 code다. 그 밖의 code는 content가 오류 본문으로 돌려준 값이라 error.code에도 남긴다
        boolean classified = "UPSTREAM_INVALID_RESPONSE".equals(failure.code()) || "CONTENT_UNAVAILABLE".equals(failure.code());
        failed(failure.code(), classified ? null : failure.code());
        String traceId = AppLogger.current().traceId();
        if ("DOCUMENT_CONFLICT".equals(failure.code()))
            return ResponseEntity.status(409).header("Cache-Control","no-store").body(new Conflict(
                    failure.code(), "The document changed. Resolve the conflict.", "NONE",
                    ContentDtos.Content.from(failure.current()), ContentDtos.Snapshot.from(failure.base()), traceId));
        // 장애·횟수 제한과 이미 진행 중인 최신화(409)는 잠시 뒤 다시 하면 된다.
        String action = failure.status() == 503 || failure.status() == 429 || "REFRESH_IN_PROGRESS".equals(failure.code())
                ? "RETRY_LATER" : "NONE";
        var details = failure.details();
        if (details == null) return error(failure.status(), failure.code(), action);
        String code = failure.code(), message = message(failure.status());
        Object body = switch (code) {
            case "REFRESH_DRAFT_CONFLICT" -> new DraftConflict(code, message, action, ContentDtos.DraftConflict.from(details.current()), traceId);
            case "REFRESH_DRAFTS_UNMATCHED" -> new UnmatchedDrafts(code, message, action, details.drafts(), traceId);
            case "REFRESH_DOCUMENTS_CHANGED" -> new ChangedDocuments(code, message, action,
                    details.documents().stream().map(ContentDtos.ChangedDocument::from).toList(), traceId);
            case "REFRESH_DRAFTS_INVALID" -> new InvalidDrafts(code, message, action, details.problems(), traceId);
            default -> new Error(code, message, action, traceId);
        };
        return ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(body);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalid() { failed("INVALID_REQUEST", null); return error(400, "INVALID_REQUEST", "NONE"); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> path() { failed("PROJECT_NOT_FOUND", null); return error(404, "PROJECT_NOT_FOUND", "NONE"); }
    private ResponseEntity<Error> error(int status, String code, String action) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new Error(code,message(status),action,AppLogger.current().traceId()));
    }
    /**
     * 처리한 실패의 이유를 결과 줄(request completed)에 맡긴다. 예외 메시지는 싣지 않는다. 상태·레벨은 결과 줄이 응답에서 정한다.
     *
     * @param code     브라우저에 돌려주는 우리 오류 code(error.type)
     * @param upstream content가 오류 본문으로 돌려준 code(error.code). gateway가 붙인 code면 null이라 줄에서 빠진다
     */
    private static void failed(String code, String upstream) {
        AppLogger.current().addToOutcome(LogField.errorType(code), LogField.errorCode(upstream));
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
