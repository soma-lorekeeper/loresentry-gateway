package com.loresentry.gateway.client.auth;

import java.util.UUID;
import java.io.InputStream;
import java.io.IOException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

@Component
public class AuthApiClient {
    private final RestClient client;
    private enum Operation { PREPARE, CALLBACK, REFRESH, REVOKE, SESSION_REVOKE, ACCOUNT }
    private static final JsonMapper JSON=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config->{
                for(var shape:java.util.List.of(tools.jackson.databind.cfg.CoercionInputShape.Integer,
                        tools.jackson.databind.cfg.CoercionInputShape.Float,tools.jackson.databind.cfg.CoercionInputShape.Boolean))
                    config.setCoercion(shape,tools.jackson.databind.cfg.CoercionAction.Fail);
            }).build();
    public AuthApiClient(RestClient authApiRestClient){this.client=authApiRestClient;}
    public AuthData.Prepared prepare() {
        return call(HttpMethod.POST,"/auth/oauth/google/prepare",null,new AuthData.Empty(),AuthData.Prepared.class,200,Operation.PREPARE);
    }
    public AuthData.LoginSession callback(AuthData.Callback input) {
        return call(HttpMethod.POST,"/auth/oauth/google/callback",null,input,AuthData.LoginSession.class,200,Operation.CALLBACK);
    }
    public AuthData.Tokens refresh(String token) {
        return call(HttpMethod.POST,"/auth/tokens/refresh",null,new AuthData.Refresh(token),AuthData.Tokens.class,200,Operation.REFRESH);
    }
    public void revoke(String token) {
        call(HttpMethod.POST,"/auth/tokens/revoke",null,new AuthData.Refresh(token),Void.class,204,Operation.REVOKE);
    }
    public void revokeSession(String id) {
        call(HttpMethod.POST,"/auth/sessions/revoke",null,new AuthData.Session(id),Void.class,204,Operation.SESSION_REVOKE);
    }
    public AuthData.Account account(UUID user) {
        return ownAccount(user,call(HttpMethod.GET,"/auth/users/me",user,null,AuthData.Account.class,200,Operation.ACCOUNT));
    }
    public AuthData.Account updateAccount(UUID user,String displayName) {
        return ownAccount(user,call(HttpMethod.PATCH,"/auth/users/me",user,new AuthData.DisplayName(displayName),AuthData.Account.class,200,Operation.ACCOUNT));
    }
    private AuthData.Account ownAccount(UUID user,AuthData.Account account) {
        if(!user.equals(account.id())) throw AuthCallFailure.invalid(null);
        return account;
    }
    private <T> T call(HttpMethod method,String path,UUID user,Object body,Class<T> type,int expected,Operation operation) {
        try {
            var request=client.method(method).uri(path);
            if(user!=null) request.header("X-User-Id",user.toString());
            if(body!=null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((outgoing,response)-> {
                int status=response.getStatusCode().value();
                if(status==expected&&type==Void.class) return null;
                var contentType=response.getHeaders().getContentType();
                if(contentType==null||!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) throw AuthCallFailure.invalid(null);
                return decode(response.getBody(),status,type,expected,operation);
            });
        } catch(ResourceAccessException unavailable) { throw AuthCallFailure.unavailable(notSent(unavailable)); }
        catch(RestClientException|IllegalArgumentException invalid) {throw AuthCallFailure.invalid(null);}
    }
    private <T> T decode(InputStream body,int status,Class<T> type,int expected,Operation operation) throws IOException {
        Boolean consumed=null;
        try {
            var tree=JSON.readTree(body);
            if(tree==null||!tree.isObject()) throw AuthCallFailure.invalid(null);
            if(operation==Operation.CALLBACK) {
                var field=tree.get("login_request_consumed");
                if(field!=null&&!field.isNull()) {
                    if(!field.isBoolean()) throw AuthCallFailure.invalid(null);
                    consumed=field.booleanValue();
                }
            }
            if(status==expected) {
                T result=JSON.treeToValue(tree,type);validate(result);
                if(operation==Operation.CALLBACK&&!Boolean.TRUE.equals(consumed)) throw AuthCallFailure.invalid(consumed);
                return result;
            }
            var error=JSON.treeToValue(tree,AuthData.Error.class);
            if(error.code()==null||error.message()==null||!known(operation,status,error.code(),error.nextAction()))
                throw AuthCallFailure.invalid(consumed);
            throw new AuthCallFailure(AuthCallFailure.Kind.CONTRACT,status,error.code(),error.nextAction(),consumed,false);
        } catch(JacksonException malformed) {
            for(Throwable cause=malformed.getCause();cause!=null;cause=cause.getCause())
                if(cause instanceof IOException) throw AuthCallFailure.unavailable(false);
            throw AuthCallFailure.invalid(consumed);
        } catch(IllegalArgumentException|NullPointerException malformed) {throw AuthCallFailure.invalid(consumed);}
    }
    private static void validate(Object response) {
        if(response instanceof AuthData.Account account) {
            java.util.Objects.requireNonNull(account.id());required(account.displayName());
        } else if(response instanceof AuthData.Prepared p) {
            required(p.authorizationUrl());required(p.loginRequestId());java.util.Objects.requireNonNull(p.expiresAt());
        } else if(response instanceof AuthData.Tokens t) {
            required(t.accessToken());required(t.refreshToken());java.util.Objects.requireNonNull(t.accessExpiresAt());java.util.Objects.requireNonNull(t.refreshExpiresAt());
        } else if(response instanceof AuthData.LoginSession t) {
            new com.loresentry.gateway.application.SessionId(t.sessionId());java.util.Objects.requireNonNull(t.expiresAt());
        }
    }
    private static void required(String value) {if(value==null||value.isBlank()) throw new IllegalArgumentException();}
    private static boolean known(Operation operation,int status,String code,String action) {
        return switch(code) {
            case "INVALID_REQUEST" -> status==400&&"NONE".equals(action);
            case "INTERNAL_ERROR" -> status==500&&"NONE".equals(action);
            case "LOGIN_UNAVAILABLE" -> (operation==Operation.PREPARE||operation==Operation.CALLBACK)&&status==503&&"RESTART_LOGIN".equals(action);
            case "OAUTH_REQUEST_INVALID","OAUTH_LOGIN_DENIED" -> operation==Operation.CALLBACK&&status==400&&"RESTART_LOGIN".equals(action);
            case "OAUTH_IDENTITY_INVALID" -> operation==Operation.CALLBACK&&status==401&&"RESTART_LOGIN".equals(action);
            case "REFRESH_REJECTED" -> operation==Operation.REFRESH&&status==401&&"RELOGIN".equals(action);
            case "REFRESH_UNAVAILABLE" -> operation==Operation.REFRESH&&status==503&&"RETRY_LATER".equals(action);
            case "REFRESH_OUTCOME_UNKNOWN" -> operation==Operation.REFRESH&&status==503&&"RELOGIN".equals(action);
            case "INVALID_REFRESH_TOKEN" -> operation==Operation.REVOKE&&status==401&&"NONE".equals(action);
            case "INVALID_SESSION_ID" -> operation==Operation.SESSION_REVOKE&&status==400&&"NONE".equals(action);
            case "REVOCATION_UNCONFIRMED" -> (operation==Operation.REVOKE||operation==Operation.SESSION_REVOKE)&&status==503&&"NONE".equals(action);
            case "INVALID_DISPLAY_NAME" -> operation==Operation.ACCOUNT&&status==400&&"NONE".equals(action);
            case "USER_CONTEXT_REQUIRED" -> operation==Operation.ACCOUNT&&status==401&&"RELOGIN".equals(action);
            case "USER_NOT_FOUND" -> operation==Operation.ACCOUNT&&status==404&&"RELOGIN".equals(action);
            case "ACCOUNT_UNAVAILABLE" -> operation==Operation.ACCOUNT&&status==503&&"RETRY_LATER".equals(action);
            default -> false;
        };
    }
    private static boolean notSent(Throwable failure) {
        // With redirects/retries disabled, these failures precede HTTP dispatch to the configured host.
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.net.ConnectException||cause instanceof java.net.UnknownHostException
                    ||cause instanceof org.apache.hc.client5.http.ConnectTimeoutException
                    ||cause instanceof org.apache.hc.core5.http.ConnectionRequestTimeoutException
                    ||cause instanceof javax.net.ssl.SSLHandshakeException) return true;
        }
        return false;
    }
}
