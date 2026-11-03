package com.loresentry.gateway.application;

import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final AuthApiClient client;
    public SessionService(AuthApiClient client){this.client=client;}
    public enum Revocation { CONFIRMED, NOT_REQUESTED, REJECTED, UNCONFIRMED }
    /**
     * 서버 세션을 폐기한다. 쿠키가 없으면 묻지 않고, 모양이 틀린 값은 auth에 보내지 않고 거절한다.
     * 결과마다 한 줄 남긴다. 세션 ID는 인증 수단이라 어느 줄에도 남기지 않는다.
     */
    public Revocation revoke(String id) {
        AppLogger.current().info("session revoke");
        if(id==null) {AppLogger.current().info("session revoke skipped: no session cookie");return Revocation.NOT_REQUESTED;}
        try {new SessionId(id);}
        catch(IllegalArgumentException invalid) {AppLogger.current().warn("session revoke rejected: malformed session id");return Revocation.REJECTED;}
        try {client.revokeSession(id);AppLogger.current().info("session revoked");return Revocation.CONFIRMED;}
        catch(AuthCallFailure failure) {
            if(failure.kind()==AuthCallFailure.Kind.CONTRACT&&"INVALID_SESSION_ID".equals(failure.code())) {
                AppLogger.current().warn("session revoke rejected by auth",LogField.errorType(failure.code()));return Revocation.REJECTED;
            }
            // 폐기됐는지 알 수 없다(auth 장애·잘못된 응답). 쿠키는 컨트롤러가 그래도 지운다
            AppLogger.current().warn("session revoke unconfirmed",LogField.errorType(failure.code()));
            return Revocation.UNCONFIRMED;
        }
    }
}
