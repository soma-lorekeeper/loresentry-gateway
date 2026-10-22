package com.loresentry.gateway.web.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.application.ChatNotEnabled;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatExceptionHandler {
    record Error(String code, String message, @JsonProperty("next_action") String nextAction) {}

    @ExceptionHandler(ChatCallFailure.class)
    ResponseEntity<Error> chat(ChatCallFailure failure) { return error(failure.status(), failure.code(), failure.nextAction()); }
    @ExceptionHandler(ChatNotEnabled.class)
    ResponseEntity<Error> disabled() { return error(403, "CHAT_NOT_ENABLED", "NONE"); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Error> invalid() { return error(400, "INVALID_REQUEST", "NONE"); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Error> path(MethodArgumentTypeMismatchException failure) {
        return error(404, "sessionId".equals(failure.getName()) ? "CHAT_SESSION_NOT_FOUND" : "PROJECT_NOT_FOUND", "NONE");
    }

    private static ResponseEntity<Error> error(int status, String code, String nextAction) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(new Error(code, message(code), nextAction));
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
            case "CHAT_MODEL_UNAVAILABLE" -> "The AI model is unavailable. Try again later.";
            case "CONTENT_UNAVAILABLE" -> "Content service is unavailable.";
            case "AI_CHAT_UNAVAILABLE" -> "AI chat service is unavailable.";
            case "UPSTREAM_INVALID_RESPONSE" -> "The service returned an invalid response.";
            default -> "Invalid request.";
        };
    }
}
