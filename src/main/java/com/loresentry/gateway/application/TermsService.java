package com.loresentry.gateway.application;

import com.loresentry.gateway.client.auth.*;
import com.loresentry.gateway.client.SupportedLocale;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 첫 로그인의 약관 조회·동의. 동의하면 auth가 세션을 발급한다.
 *
 * <p>실패와 동의 완료를 한 줄씩 남긴다. 동의 요청 ID·약관 버전·세션 ID는 남기지 않는다. locale은 허용 목록 안의 코드만 남긴다.
 */
@Service
public final class TermsService {
    private final AuthApiClient client;
    private final Clock clock;
    public TermsService(AuthApiClient client,Clock clock){this.client=client;this.clock=clock;}
    public AuthData.Terms query(ConsentId id,String locale) {
        AppLogger.current().info("terms get",LogField.code("locale",locale,SupportedLocale.VALUES));
        try {
            var result=client.terms(id.value(),locale);
            validExpiry(result.expiresAt());return result;
        } catch(AuthCallFailure failure){AppLogger.current().warn("terms lookup failed",LogField.errorType(failure.reason()),LogField.errorCode(failure.upstreamCode()));throw mapped(failure);}
    }
    public LoginService.Session accept(ConsentId id,String version) {
        AppLogger.current().info("terms accept");
        try {
            if(!UUID.fromString(version).toString().equals(version))throw new IllegalArgumentException();
        } catch(RuntimeException invalid){
            AppLogger.current().warn("terms accept rejected: malformed terms version id");
            throw new AuthOperationFailure(400,"INVALID_REQUEST","NONE");
        }
        try {
            var result=client.acceptTerms(id.value(),version);
            validExpiry(result.expiresAt());
            var session=new LoginService.Session(new SessionId(result.sessionId()),result.expiresAt());
            AppLogger.current().info("terms accepted");
            return session;
        } catch(AuthCallFailure failure){AppLogger.current().warn("terms accept failed",LogField.errorType(failure.reason()),LogField.errorCode(failure.upstreamCode()));throw mapped(failure);}
    }
    private void validExpiry(Instant expiry) {
        // 만료가 없거나 이미 지난 응답은 auth 계약 위반이다
        if(expiry==null||!expiry.isAfter(clock.instant().plusSeconds(1))) {
            AppLogger.current().warn("auth response expiry is invalid",LogField.errorType("UPSTREAM_INVALID_RESPONSE"));
            throw new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");
        }
    }
    private AuthOperationFailure mapped(AuthCallFailure failure) {
        if(failure.kind()==AuthCallFailure.Kind.CONTRACT)return new AuthOperationFailure(failure.status(),failure.code(),failure.nextAction(),failure.upstreamCode());
        if(failure.kind()==AuthCallFailure.Kind.UNAVAILABLE)return new AuthOperationFailure(503,"LOGIN_UNAVAILABLE","RESTART_LOGIN");
        return new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");
    }
}
