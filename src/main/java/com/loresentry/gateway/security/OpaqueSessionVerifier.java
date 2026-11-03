package com.loresentry.gateway.security;

import com.loresentry.gateway.logging.LogLayer;
import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.client.session.OpaqueSessionReader;
import com.loresentry.gateway.client.session.SessionLookupFailure;

// log.layer: 세션 확인 정책(형식·만료)을 판단하므로 security 레이어다(클래스 이름 끝 규칙에 맞지 않아 직접 지정)
@LogLayer("security")
public final class OpaqueSessionVerifier {
    private final OpaqueSessionReader reader;
    public OpaqueSessionVerifier(OpaqueSessionReader reader) { this.reader = reader; }

    public OpaqueSessionReader.VerifiedSession verify(String value) {
        if (value == null) throw new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED);
        SessionId id;
        try { id = new SessionId(value); }
        catch (IllegalArgumentException invalid) { throw new SecurityFailure(SecurityFailure.Reason.SESSION_INVALID); }
        try { return reader.verifyAndExtend(id); }
        catch (SessionLookupFailure failure) {
            throw new SecurityFailure(failure.kind() == SessionLookupFailure.Kind.INVALID
                    ? SecurityFailure.Reason.SESSION_INVALID : SecurityFailure.Reason.SESSION_UNAVAILABLE);
        }
    }
}
