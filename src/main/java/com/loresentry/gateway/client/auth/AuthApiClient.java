package com.loresentry.gateway.client.auth;

import java.util.UUID;
import java.io.InputStream;
import java.io.IOException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.loresentry.gateway.client.SupportedLocale;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
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
    private enum Operation { PREPARE, CALLBACK, SESSION_REVOKE, ACCOUNT, TERMS }
    /** 로그용: auth 오류 계약에 있는 code(known의 case와 같다). 이 안의 code만 error.code로 남긴다. 밖의 값은 auth가 보낸 임의 문자열일 수 있다 */
    private static final java.util.Set<String> CODES=java.util.Set.of("INVALID_REQUEST","INTERNAL_ERROR","LOGIN_UNAVAILABLE","CONSENT_REQUEST_INVALID",
            "TERMS_VERSION_MISMATCH","OAUTH_REQUEST_INVALID","OAUTH_LOGIN_DENIED","OAUTH_IDENTITY_INVALID","INVALID_SESSION_ID","REVOCATION_UNCONFIRMED",
            "INVALID_DISPLAY_NAME","USER_CONTEXT_REQUIRED","USER_NOT_FOUND","ACCOUNT_UNAVAILABLE");
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
        AppLogger.current().info("auth oauth login prepare call");
        return call(HttpMethod.POST,"/auth/oauth/google/prepare",null,new AuthData.Empty(),AuthData.Prepared.class,200,Operation.PREPARE);
    }
    public AuthData.LoginSession callback(AuthData.Callback input) {
        // 로그인 요청 ID·OAuth code·state·error는 로그인 과정의 비밀 값이라 남기지 않는다
        AppLogger.current().info("auth oauth login callback call");
        return call(HttpMethod.POST,"/auth/oauth/google/callback",null,input,AuthData.LoginSession.class,200,Operation.CALLBACK);
    }
    public void revokeSession(String id) {
        // 세션 ID는 인증 수단이라 남기지 않는다
        AppLogger.current().info("auth session revoke call");
        call(HttpMethod.POST,"/auth/sessions/revoke",null,new AuthData.Session(id),Void.class,204,Operation.SESSION_REVOKE);
    }
    public AuthData.Terms terms(String consentId,String locale) {
        // 동의 요청 ID는 로그인 과정을 이어 갈 수 있는 값이라 남기지 않는다
        AppLogger.current().info("auth terms get call",LogField.code("locale",locale,SupportedLocale.VALUES));
        var path=SupportedLocale.valid(locale)?"/auth/terms?locale="+locale:"/auth/terms";
        return call(HttpMethod.GET,path,null,null,AuthData.Terms.class,200,Operation.TERMS,consentId);
    }
    public AuthData.AcceptedTerms acceptTerms(String consentId,String version) {
        AppLogger.current().info("auth terms accept call",LogField.param("terms_version_id",version));
        return call(HttpMethod.POST,"/auth/terms/accept",null,new AuthData.AcceptTerms(consentId,version),AuthData.AcceptedTerms.class,200,Operation.TERMS);
    }
    public AuthData.Account account(UUID user) {
        AppLogger.current().info("auth account get call");
        return ownAccount(user,call(HttpMethod.GET,"/auth/users/me",user,null,AuthData.Account.class,200,Operation.ACCOUNT));
    }
    public AuthData.Account updateAccount(UUID user,String displayName) {
        // 표시 이름은 개인정보라 남기지 않는다
        AppLogger.current().info("auth account update call");
        return ownAccount(user,call(HttpMethod.PATCH,"/auth/users/me",user,new AuthData.DisplayName(displayName),AuthData.Account.class,200,Operation.ACCOUNT));
    }
    public AuthData.Account updateLocale(UUID user,String locale) {
        AppLogger.current().info("auth account locale update call",LogField.code("locale",locale,SupportedLocale.VALUES));
        return ownAccount(user,call(HttpMethod.PUT,"/auth/users/me/locale",user,new AuthData.AccountLocale(locale),AuthData.Account.class,200,Operation.ACCOUNT));
    }
    public void completeOnboarding(UUID user) {
        AppLogger.current().info("auth account onboarding complete call");
        call(HttpMethod.PUT,"/auth/users/me/onboarding",user,null,Void.class,204,Operation.ACCOUNT);
    }
    public void recordAcquisition(UUID user,AuthData.Acquisition acquisition) {
        // 광고 클릭 ID 값(gclid 등)은 클릭 하나를 특정하므로 남기지 않는다. 나머지 유입 값은 길이만 남는다
        AppLogger.current().info("auth account acquisition record call",
                LogField.param("utm_source",acquisition==null?null:acquisition.utmSource()),LogField.param("utm_medium",acquisition==null?null:acquisition.utmMedium()),
                LogField.param("utm_campaign",acquisition==null?null:acquisition.utmCampaign()),LogField.param("utm_term",acquisition==null?null:acquisition.utmTerm()),
                LogField.param("utm_content",acquisition==null?null:acquisition.utmContent()),LogField.param("click_id_type",acquisition==null?null:acquisition.clickIdType()),
                LogField.param("referrer_host",acquisition==null?null:acquisition.referrerHost()),LogField.param("landing_path",acquisition==null?null:acquisition.landingPath()),
                LogField.param("landed_at",acquisition==null?null:acquisition.landedAt()));
        call(HttpMethod.PUT,"/auth/users/me/acquisition",user,acquisition,Void.class,204,Operation.ACCOUNT);
    }
    public void deleteAccount(UUID user) {
        AppLogger.current().info("auth account delete call");
        call(HttpMethod.DELETE,"/auth/users/me",user,null,Void.class,204,Operation.ACCOUNT);
    }
    private AuthData.Account ownAccount(UUID user,AuthData.Account account) {
        if(!user.equals(account.id())) {
            // 요청한 사용자와 다른 계정이 왔다. 계정 내용은 남기지 않는다
            AppLogger.current().warn("auth response rejected",LogField.errorType("ACCOUNT_ID_MISMATCH"));
            throw AuthCallFailure.invalid(null);
        }
        return account;
    }
    private <T> T call(HttpMethod method,String path,UUID user,Object body,Class<T> type,int expected,Operation operation) {
        return call(method,path,user,body,type,expected,operation,null);
    }
    private <T> T call(HttpMethod method,String path,UUID user,Object body,Class<T> type,int expected,Operation operation,String consentId) {
        try {
            var request=client.method(method).uri(path);
            if(user!=null) request.header("X-User-Id",user.toString());
            if(consentId!=null) request.header("X-Consent-Request-Id",consentId);
            if(body!=null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((outgoing,response)-> {
                int status=response.getStatusCode().value();
                if(status==expected&&type==Void.class) return null;
                var contentType=response.getHeaders().getContentType();
                if(contentType==null||!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                    AppLogger.current().warn("auth response rejected",LogField.errorType("UNEXPECTED_CONTENT_TYPE"),LogField.statusCode(status));
                    throw AuthCallFailure.invalid(null);
                }
                return decode(response.getBody(),status,type,expected,operation);
            });
        } catch(ResourceAccessException unavailable) {
            // 연결·전송 실패는 UpstreamCallLogInterceptor가 "upstream call failed"로 이미 남긴다
            throw AuthCallFailure.unavailable(notSent(unavailable));
        }
        catch(RestClientException|IllegalArgumentException invalid) {
            AppLogger.current().warn("auth response rejected",LogField.errorType(invalid.getClass().getName()));
            throw AuthCallFailure.invalid(null);
        }
    }
    private <T> T decode(InputStream body,int status,Class<T> type,int expected,Operation operation) throws IOException {
        Boolean consumed=null;
        try {
            var tree=JSON.readTree(body);
            if(tree==null||!tree.isObject()) {
                AppLogger.current().warn("auth response rejected",LogField.errorType("UNEXPECTED_BODY"),LogField.statusCode(status));
                throw AuthCallFailure.invalid(null);
            }
            if(operation==Operation.CALLBACK) {
                var field=tree.get("login_request_consumed");
                if(field!=null&&!field.isNull()) {
                    if(!field.isBoolean()) {
                        AppLogger.current().warn("auth response rejected",LogField.errorType("UNEXPECTED_BODY"),LogField.statusCode(status));
                        throw AuthCallFailure.invalid(null);
                    }
                    consumed=field.booleanValue();
                }
            }
            if(status==expected) {
                T result=JSON.treeToValue(tree,type);validate(result);
                if(operation==Operation.CALLBACK&&!Boolean.TRUE.equals(consumed)) {
                    // 로그인 성공인데 auth가 로그인 요청을 소비했다고 알리지 않았다
                    AppLogger.current().warn("auth response rejected",LogField.errorType("LOGIN_REQUEST_NOT_CONSUMED"),LogField.statusCode(status));
                    throw AuthCallFailure.invalid(consumed);
                }
                return result;
            }
            var error=JSON.treeToValue(tree,AuthData.Error.class);
            if(error.code()==null||error.message()==null||!known(operation,status,error.code(),error.nextAction())) {
                // code·message가 없거나, 모르는 code이거나, 아는 code인데 작업·상태·next_action이 계약과 다르다
                boolean knownCode=error.code()!=null&&CODES.contains(error.code());
                AppLogger.current().warn("auth response rejected",
                        LogField.errorType(error.code()==null||error.message()==null?"MISSING_FIELD":knownCode?"ERROR_CONTRACT_MISMATCH":"UNKNOWN_ERROR_CODE"),
                        LogField.errorCode(knownCode?error.code():null),LogField.statusCode(status));
                throw AuthCallFailure.invalid(consumed);
            }
            throw new AuthCallFailure(AuthCallFailure.Kind.CONTRACT,status,error.code(),error.nextAction(),consumed,false);
        } catch(JacksonException malformed) {
            for(Throwable cause=malformed.getCause();cause!=null;cause=cause.getCause())
                if(cause instanceof IOException) {
                    AppLogger.current().warn("auth response read failed",LogField.errorType(cause.getClass().getName()));
                    throw AuthCallFailure.unavailable(false);
                }
            AppLogger.current().warn("auth response rejected",LogField.errorType(malformed.getClass().getName()),LogField.statusCode(status));
            throw AuthCallFailure.invalid(consumed);
        } catch(IllegalArgumentException|NullPointerException malformed) {
            // 응답 검증(validate)에서 빠진 필드·허용하지 않는 값 등. 예외 메시지는 남기지 않는다
            AppLogger.current().warn("auth response rejected",LogField.errorType(malformed.getClass().getName()),LogField.statusCode(status));
            throw AuthCallFailure.invalid(consumed);
        }
    }
    private static void validate(Object response) {
        if(response instanceof AuthData.Account account) {
            java.util.Objects.requireNonNull(account.id());required(account.displayName());
            java.util.Objects.requireNonNull(account.onboardingCompleted());locale(account.locale());
        } else if(response instanceof AuthData.Prepared p) {
            required(p.authorizationUrl());required(p.loginRequestId());java.util.Objects.requireNonNull(p.expiresAt());
        } else if(response instanceof AuthData.LoginSession t) {
            if("AUTHENTICATED".equals(t.status()) && t.consentRequestId()==null) new com.loresentry.gateway.application.SessionId(t.sessionId());
            else if("TERMS_REQUIRED".equals(t.status()) && t.sessionId()==null) new com.loresentry.gateway.application.ConsentId(t.consentRequestId());
            else throw new IllegalArgumentException();
            java.util.Objects.requireNonNull(t.expiresAt());
        } else if(response instanceof AuthData.Terms t) {
            if(!UUID.fromString(t.termsVersionId()).toString().equals(t.termsVersionId())) throw new IllegalArgumentException();
            required(t.version());required(t.title());required(t.content());
            java.util.Objects.requireNonNull(t.effectiveAt());java.util.Objects.requireNonNull(t.expiresAt());locale(t.locale());
        } else if(response instanceof AuthData.AcceptedTerms t) {
            new com.loresentry.gateway.application.SessionId(t.sessionId());java.util.Objects.requireNonNull(t.expiresAt());
        }
    }
    private static void required(String value) {if(value==null||value.isBlank()) throw new IllegalArgumentException();}
    private static void locale(String value) {if(value!=null&&!SupportedLocale.valid(value)) throw new IllegalArgumentException();}
    private static boolean known(Operation operation,int status,String code,String action) {
        return switch(code) {
            case "INVALID_REQUEST" -> status==400&&"NONE".equals(action);
            case "INTERNAL_ERROR" -> operation!=Operation.TERMS&&status==500&&"NONE".equals(action);
            case "LOGIN_UNAVAILABLE" -> (operation==Operation.PREPARE||operation==Operation.CALLBACK||operation==Operation.TERMS)&&status==503&&"RESTART_LOGIN".equals(action);
            case "CONSENT_REQUEST_INVALID" -> operation==Operation.TERMS&&status==401&&"RESTART_LOGIN".equals(action);
            case "TERMS_VERSION_MISMATCH" -> operation==Operation.TERMS&&status==409&&"NONE".equals(action);
            case "OAUTH_REQUEST_INVALID","OAUTH_LOGIN_DENIED" -> operation==Operation.CALLBACK&&status==400&&"RESTART_LOGIN".equals(action);
            case "OAUTH_IDENTITY_INVALID" -> operation==Operation.CALLBACK&&status==401&&"RESTART_LOGIN".equals(action);
            case "INVALID_SESSION_ID" -> operation==Operation.SESSION_REVOKE&&status==400&&"NONE".equals(action);
            case "REVOCATION_UNCONFIRMED" -> operation==Operation.SESSION_REVOKE&&status==503&&"NONE".equals(action);
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
