package com.loresentry.gateway.web.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.application.AuthOperationFailure;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 계정·약관·로그아웃 요청의 실패를 브라우저용 오류 본문으로 바꾼다.
 *
 * <p>실패 이유(브라우저에 돌려주는 code)를 결과 줄(request completed)에 맡기고, 본문에 trace_id를 싣는다. 잘못된 본문(400)도
 * {@link #failure}를 거친다. trace_id는 추적이 없으면(trace 배선이 없는 테스트) null이라 본문에서 빠진다.
 */
@RestControllerAdvice(assignableTypes={SessionController.class,AccountController.class,TermsController.class})
public class AuthExceptionHandler {
    public record Error(String code,String message,@JsonProperty("next_action") String nextAction,
            @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {}
    @ExceptionHandler(AuthOperationFailure.class)
    public ResponseEntity<Error> failure(AuthOperationFailure failure) {
        // error.type은 브라우저에 돌려주는 code다. auth가 돌려준 code를 그대로 넘긴 경우도 있지만 여기서는 구분할 수 없어 error.code는 비운다.
        // 상태·레벨은 결과 줄이 응답에서 정한다
        AppLogger.current().addToOutcome(LogField.errorType(failure.code()));
        return ResponseEntity.status(failure.status()).header("Cache-Control","no-store")
                .body(new Error(failure.code(),"The authentication request could not be completed.",failure.nextAction(),AppLogger.current().traceId()));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Error> invalidInput() {return failure(new AuthOperationFailure(400,"INVALID_REQUEST","NONE"));}
}
