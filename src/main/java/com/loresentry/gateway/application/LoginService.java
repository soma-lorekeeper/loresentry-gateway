package com.loresentry.gateway.application;

import java.time.Instant;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthData;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

/**
 * Google 로그인. prepare는 auth에서 Google 인가 주소를 받고, callback은 돌아온 code로 세션 또는 약관 동의 요청을 받는다.
 *
 * <p>결과마다 한 줄 남긴다. 로그인 요청 ID, OAuth code·state·error, 세션·동의 요청 ID는 로그인 과정의 비밀 값이라 남기지 않는다.
 */
@Service
public class LoginService {
    private final AuthApiClient client;
    public LoginService(AuthApiClient client) {this.client=client;}
    public enum Result { SUCCESS, TERMS_REQUIRED, CANCELLED, INVALID, UNAVAILABLE, FAILED }
    public record Preparation(String authorizationUrl,String requestId,Instant expiresAt,Result result) {
        @Override public String toString(){return "Preparation[result="+result+"]";}
    }
    public record Session(SessionId id,Instant expiresAt) {}
    public record Consent(ConsentId id,Instant expiresAt) {}
    public record CallbackResult(Session session,Consent consent,Result result,Boolean consumed) {
        public CallbackResult(Session session,Result result,Boolean consumed) { this(session,null,result,consumed); }
    }
    public Preparation prepare() {
        AppLogger.current().info("oauth login prepare");
        try {
            var response=client.prepare();
            return new Preparation(response.authorizationUrl(),response.loginRequestId(),response.expiresAt(),Result.SUCCESS);
        } catch(AuthCallFailure failure) {
            AppLogger.current().warn("oauth login prepare failed",LogField.errorType(failure.reason()),LogField.errorCode(failure.upstreamCode()));
            return new Preparation(null,null,null,result(failure));
        }
    }
    public CallbackResult callback(String requestId,String state,String code,String error) {
        AppLogger.current().info("oauth login callback");
        // 로그인 요청 쿠키·state가 없거나, code와 error가 둘 다 있거나 둘 다 없으면 auth에 묻지 않고 거절한다
        if(blank(requestId)||blank(state)||(blank(code)==blank(error))) {
            AppLogger.current().warn("oauth login callback rejected: incomplete parameters");
            return new CallbackResult(null,Result.INVALID,null);
        }
        try {
            var response=client.callback(new AuthData.Callback(requestId,state,code,error));
            if("TERMS_REQUIRED".equals(response.status())) {
                var consent=new Consent(new ConsentId(response.consentRequestId()),response.expiresAt());
                AppLogger.current().info("oauth login requires terms acceptance");
                return new CallbackResult(null,consent,Result.TERMS_REQUIRED,response.consumed());
            }
            var session=new Session(new SessionId(response.sessionId()),response.expiresAt());
            AppLogger.current().info("oauth login session issued");
            return new CallbackResult(session,Result.SUCCESS,response.consumed());
        } catch(AuthCallFailure failure) {
            var result=result(failure);
            // 사용자가 Google 화면에서 취소한 것은 정상 흐름이라 info로 남긴다
            if(result==Result.CANCELLED) AppLogger.current().info("oauth login cancelled by user");
            else AppLogger.current().warn("oauth login callback failed",LogField.errorType(failure.reason()),LogField.errorCode(failure.upstreamCode()));
            return new CallbackResult(null,result,failure.consumed());
        }
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static Result result(AuthCallFailure failure) {
        if(failure.kind()==AuthCallFailure.Kind.UNAVAILABLE) return Result.UNAVAILABLE;
        return switch(failure.code()) {
            case "OAUTH_LOGIN_DENIED" -> Result.CANCELLED;
            case "OAUTH_REQUEST_INVALID" -> Result.INVALID;
            case "LOGIN_UNAVAILABLE" -> Result.UNAVAILABLE;
            default -> Result.FAILED;
        };
    }
}
