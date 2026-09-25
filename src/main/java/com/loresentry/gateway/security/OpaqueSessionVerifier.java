package com.loresentry.gateway.security;

import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.client.session.OpaqueSessionReader;
import com.loresentry.gateway.client.session.SessionLookupFailure;

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
