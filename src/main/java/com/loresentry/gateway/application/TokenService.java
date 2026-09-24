package com.loresentry.gateway.application;

import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final AuthApiClient client;
    public TokenService(AuthApiClient client){this.client=client;}
    public TokenPair refresh(String refreshToken) {
        if(refreshToken==null||refreshToken.isBlank()) throw AuthOperationFailure.refreshRejected();
        try {
            var pair=client.refresh(refreshToken);
            return new TokenPair(pair.accessToken(),pair.accessExpiresAt(),pair.refreshToken(),pair.refreshExpiresAt());
        } catch(AuthCallFailure failure) {
            if(failure.kind()==AuthCallFailure.Kind.CONTRACT)
                throw new AuthOperationFailure(failure.status(),failure.code(),failure.nextAction());
            if(failure.kind()==AuthCallFailure.Kind.UNAVAILABLE&&failure.notSent())
                throw new AuthOperationFailure(503,"REFRESH_UNAVAILABLE","RETRY_LATER");
            throw AuthOperationFailure.refreshUnknown();
        }
    }
    public enum Revocation { CONFIRMED, NOT_REQUESTED, REJECTED, UNCONFIRMED }
    public Revocation revoke(String refreshToken) {
        if(refreshToken==null||refreshToken.isBlank()) return Revocation.NOT_REQUESTED;
        try {client.revoke(refreshToken);return Revocation.CONFIRMED;}
        catch(AuthCallFailure failure) {
            if(failure.kind()==AuthCallFailure.Kind.CONTRACT&&"INVALID_REFRESH_TOKEN".equals(failure.code()))
                return Revocation.REJECTED;
            return Revocation.UNCONFIRMED;
        }
    }
}
