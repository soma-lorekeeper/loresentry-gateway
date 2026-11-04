package com.loresentry.gateway.web.chat;

import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.application.ChatNotEnabled;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * AI 채팅·설정 검사 요청의 실패를 브라우저용 오류 본문으로 바꾼다.
 *
 * <p>실패마다 {@link #error}에서 실패 이유를 결과 줄(request completed)에 맡기고, 본문에 trace_id를 싣는다.
 * trace_id는 문의 받은 값을 Kibana에서 바로 찾는 trace ID다. 추적이 없으면(trace 배선이 없는 테스트) null이라 본문에서 빠진다.
 */
@RestControllerAdvice(assignableTypes = {ChatController.class, SettingCheckController.class})
public class ChatExceptionHandler {
    /** gateway가 ai-chat 호출 실패에 스스로 붙이는 code(ChatCallFailure.invalid·unavailable). ai-chat이 돌려준 code가 아니다. */
    private static final Set<String> GATEWAY_CODES = Set.of("UPSTREAM_INVALID_RESPONSE", "AI_CHAT_UNAVAILABLE");
    record Error(String code, String message, @JsonProperty("next_action") String nextAction,
                 @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}

    @ExceptionHandler(ChatCallFailure.class)
    ResponseEntity<Error> chat(ChatCallFailure failure) {
        // 잘못된 응답·연결 실패는 gateway가 붙인 code다. 그 밖의 code는 ai-chat이 오류 본문으로 돌려준 값이라 error.code에도 남긴다
        AppLogger.current().addToOutcome(LogField.upstreamErrorCode(failure.code(), GATEWAY_CODES));
        return error(failure.status(), failure.code(), failure.nextAction());
    }
    @ExceptionHandler(ChatNotEnabled.class)
    ResponseEntity<Error> disabled() { return error(403, "CHAT_NOT_ENABLED", "NONE"); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Error> invalid() { return error(400, "INVALID_REQUEST", "NONE"); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Error> path(MethodArgumentTypeMismatchException failure) {
        return switch (failure.getName()) {
            case "projectId" -> error(404, "PROJECT_NOT_FOUND", "NONE");
            case "sessionId" -> error(404, "CHAT_SESSION_NOT_FOUND", "NONE");
            case "runId" -> error(404, "SETTING_CHECK_NOT_FOUND", "NONE");
            case "findingId" -> error(404, "SETTING_CHECK_FINDING_NOT_FOUND", "NONE");
            default -> invalid();
        };
    }

    /** 오류 본문을 만들고 처리한 실패의 이유(error.type)를 결과 줄에 맡긴다. 예외 메시지는 싣지 않는다. 상태·레벨은 결과 줄이 응답에서 정한다. */
    private static ResponseEntity<Error> error(int status, String code, String nextAction) {
        AppLogger.current().addToOutcome(LogField.errorType(code));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(new Error(code, message(code), nextAction, AppLogger.current().traceId()));
    }

    private static String message(String code) {
        return switch (code) {
            case "CHAT_NOT_ENABLED" -> "AI chat is not available for this account yet.";
            case "CHAT_SESSION_NOT_FOUND" -> "The conversation was not found.";
            case "PROJECT_NOT_FOUND" -> "The requested resource was not found.";
            case "CHAT_TITLE_REQUIRED" -> "A conversation title is required.";
            case "CHAT_QUESTION_REQUIRED" -> "A question or a quote is required.";
            case "CHAT_NOT_LAST_QUESTION" -> "Only the last question can be replaced.";
            case "CHAT_ANSWER_IN_PROGRESS" -> "An answer is already being generated.";
            case "CHAT_ANOTHER_ANSWER_IN_PROGRESS" -> "An answer is being generated in another conversation.";
            case "CHAT_CONTEXT_TOO_LARGE" -> "The project is too large for AI chat.";
            case "CHAT_DAILY_LIMIT_REACHED" -> "Today's chat usage is used up.";
            case "SETTING_CHECK_NOT_FOUND" -> "The setting check was not found.";
            case "SETTING_CHECK_FINDING_NOT_FOUND" -> "The setting check finding was not found.";
            case "SETTING_CHECK_DOCUMENT_NOT_FOUND" -> "The chapter to check was not found.";
            case "SETTING_CHECK_IN_PROGRESS" -> "A setting check is already running in this project.";
            case "CHAT_MODEL_UNAVAILABLE" -> "The AI model is unavailable. Try again later.";
            case "CONTENT_UNAVAILABLE" -> "Content service is unavailable.";
            case "AI_CHAT_UNAVAILABLE" -> "AI chat service is unavailable.";
            case "UPSTREAM_INVALID_RESPONSE" -> "The service returned an invalid response.";
            default -> "Invalid request.";
        };
    }
}
