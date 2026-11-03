package com.loresentry.gateway.security;

import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogLayer;
import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.client.session.OpaqueSessionReader;
import com.loresentry.gateway.client.session.SessionLookupFailure;

// log.layer: 세션 확인 정책(형식·만료)을 판단하므로 security 레이어다(클래스 이름 끝 규칙에 맞지 않아 직접 지정)
@LogLayer("security")
public final class OpaqueSessionVerifier {
    private final OpaqueSessionReader reader;
    public OpaqueSessionVerifier(OpaqueSessionReader reader) { this.reader = reader; }

    /**
     * 세션 쿠키 값을 확인하고 연장한다. 진입 줄과 결과 줄(확인됨·쿠키 없음·형식 오류·만료 또는 폐기·저장소 장애)을 하나씩 남긴다.
     * 세션 ID와 그 해시는 인증 수단이라 어느 줄에도 남기지 않는다. 사용자는 확인한 뒤 SessionFilter가 줄에 붙인다.
     */
    public OpaqueSessionReader.VerifiedSession verify(String value) {
        AppLogger.current().info("session verify");
        if (value == null) {
            AppLogger.current().info("session verify rejected: no session cookie");
            throw new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED);
        }
        SessionId id;
        try { id = new SessionId(value); }
        catch (IllegalArgumentException invalid) {
            AppLogger.current().warn("session verify rejected: malformed session id");
            throw new SecurityFailure(SecurityFailure.Reason.SESSION_INVALID);
        }
        try {
            var verified = reader.verifyAndExtend(id);
            AppLogger.current().info("session verified");
            return verified;
        }
        catch (SessionLookupFailure failure) {
            // INVALID는 저장소에 세션이 없거나(만료·폐기) 재확인에서 거절된 것이다. 저장소 장애의 단계·원인은 reader가 warn으로 남긴다
            if (failure.kind() == SessionLookupFailure.Kind.INVALID) AppLogger.current().info("session verify rejected: session expired or revoked");
            else AppLogger.current().warn("session verify failed: session store unavailable");
            throw new SecurityFailure(failure.kind() == SessionLookupFailure.Kind.INVALID
                    ? SecurityFailure.Reason.SESSION_INVALID : SecurityFailure.Reason.SESSION_UNAVAILABLE);
        }
    }
}
