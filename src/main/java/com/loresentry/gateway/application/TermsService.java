package com.loresentry.gateway.application;

import com.loresentry.gateway.client.auth.*;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class TermsService {
    private final AuthApiClient client;
    private final Clock clock;
    public TermsService(AuthApiClient client,Clock clock){this.client=client;this.clock=clock;}
    public AuthData.Terms query(ConsentId id) {
        try {
            var result=client.terms(id.value());
            validExpiry(result.expiresAt());return result;
        } catch(AuthCallFailure failure){throw mapped(failure);}
    }
    public LoginService.Session accept(ConsentId id,String version) {
        try {
            if(!UUID.fromString(version).toString().equals(version))throw new IllegalArgumentException();
        } catch(RuntimeException invalid){throw new AuthOperationFailure(400,"INVALID_REQUEST","NONE");}
        try {
            var result=client.acceptTerms(id.value(),version);
            validExpiry(result.expiresAt());
            return new LoginService.Session(new SessionId(result.sessionId()),result.expiresAt());
        } catch(AuthCallFailure failure){throw mapped(failure);}
    }
    private void validExpiry(Instant expiry) {
        if(expiry==null||!expiry.isAfter(clock.instant().plusSeconds(1)))throw new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");
    }
    private AuthOperationFailure mapped(AuthCallFailure failure) {
        if(failure.kind()==AuthCallFailure.Kind.CONTRACT)return new AuthOperationFailure(failure.status(),failure.code(),failure.nextAction());
        if(failure.kind()==AuthCallFailure.Kind.UNAVAILABLE)return new AuthOperationFailure(503,"LOGIN_UNAVAILABLE","RESTART_LOGIN");
        return new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");
    }
}
