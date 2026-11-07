package com.loresentry.gateway.application;

import com.loresentry.gateway.client.auth.*;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public final class PasswordLoginService {
    public record Outcome(LoginService.Session session,LoginService.Consent consent) {
        public boolean authenticated(){return session!=null;}
    }
    private final AuthApiClient client;
    private final Clock clock;
    public PasswordLoginService(AuthApiClient client,Clock clock){this.client=client;this.clock=clock;}
    public Outcome signUp(String loginId,String password,String displayName) {
        requireText(loginId);requireText(password);
        if(displayName!=null&&displayName.isBlank()) displayName=null;
        try {return outcome(client.signUp(new AuthData.SignUp(loginId,password,displayName)));}
        catch(AuthCallFailure failure){throw mapped(failure);}
    }
    public Outcome login(String loginId,String password) {
        requireText(loginId);requireText(password);
        try {return outcome(client.passwordLogin(new AuthData.PasswordLogin(loginId,password)));}
        catch(AuthCallFailure failure){throw mapped(failure);}
    }
    private Outcome outcome(AuthData.LoginSession result) {
        validExpiry(result.expiresAt());
        if("TERMS_REQUIRED".equals(result.status()))
            return new Outcome(null,new LoginService.Consent(new ConsentId(result.consentRequestId()),result.expiresAt()));
        return new Outcome(new LoginService.Session(new SessionId(result.sessionId()),result.expiresAt()),null);
    }
    private static void requireText(String value) {
        if(value==null||value.isEmpty()) throw new AuthOperationFailure(400,"INVALID_REQUEST","NONE");
    }
    private void validExpiry(Instant expiry) {
        if(expiry==null||!expiry.isAfter(clock.instant().plusSeconds(1)))throw new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");
    }
    private static AuthOperationFailure mapped(AuthCallFailure failure) {
        if(failure.kind()==AuthCallFailure.Kind.CONTRACT)return new AuthOperationFailure(failure.status(),failure.code(),failure.nextAction());
        if(failure.kind()==AuthCallFailure.Kind.UNAVAILABLE)return new AuthOperationFailure(503,"LOGIN_UNAVAILABLE","RESTART_LOGIN");
        return new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");
    }
}
