package com.loresentry.gateway.application;

import java.time.Instant;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthData;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private final AuthApiClient client;
    public LoginService(AuthApiClient client) {this.client=client;}
    public enum Result { SUCCESS, CANCELLED, INVALID, UNAVAILABLE, FAILED }
    public record Preparation(String authorizationUrl,String requestId,Instant expiresAt,Result result) {
        @Override public String toString(){return "Preparation[result="+result+"]";}
    }
    public record CallbackResult(TokenPair tokens,Result result,Boolean consumed) {}
    public Preparation prepare() {
        try {
            var response=client.prepare();
            return new Preparation(response.authorizationUrl(),response.loginRequestId(),response.expiresAt(),Result.SUCCESS);
        } catch(AuthCallFailure failure) {return new Preparation(null,null,null,result(failure));}
    }
    public CallbackResult callback(String requestId,String state,String code,String error) {
        if(blank(requestId)||blank(state)||(blank(code)==blank(error))) return new CallbackResult(null,Result.INVALID,null);
        try {
            var response=client.callback(new AuthData.Callback(requestId,state,code,error));
            var tokens=new TokenPair(response.accessToken(),response.accessExpiresAt(),response.refreshToken(),response.refreshExpiresAt());
            return new CallbackResult(tokens,Result.SUCCESS,response.consumed());
        } catch(AuthCallFailure failure) {return new CallbackResult(null,result(failure),failure.consumed());}
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
