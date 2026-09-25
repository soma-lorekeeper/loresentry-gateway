package com.loresentry.gateway.web.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.application.AuthOperationFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={SessionController.class,AccountController.class})
public class AuthExceptionHandler {
    public record Error(String code,String message,@JsonProperty("next_action") String nextAction) {}
    @ExceptionHandler(AuthOperationFailure.class)
    public ResponseEntity<Error> failure(AuthOperationFailure failure) {
        return ResponseEntity.status(failure.status()).header("Cache-Control","no-store")
                .body(new Error(failure.code(),"The authentication request could not be completed.",failure.nextAction()));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Error> invalidInput() {return failure(new AuthOperationFailure(400,"INVALID_REQUEST","NONE"));}
}
