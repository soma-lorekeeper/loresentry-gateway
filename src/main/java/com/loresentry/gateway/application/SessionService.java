package com.loresentry.gateway.application;

import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final AuthApiClient client;
    public SessionService(AuthApiClient client){this.client=client;}
    public enum Revocation { CONFIRMED, NOT_REQUESTED, REJECTED, UNCONFIRMED }
    public Revocation revoke(String id) {
        if(id==null) return Revocation.NOT_REQUESTED;
        try {new SessionId(id);} catch(IllegalArgumentException invalid) {return Revocation.REJECTED;}
        try {client.revokeSession(id);return Revocation.CONFIRMED;}
        catch(AuthCallFailure failure) {
            if(failure.kind()==AuthCallFailure.Kind.CONTRACT&&"INVALID_SESSION_ID".equals(failure.code())) return Revocation.REJECTED;
            return Revocation.UNCONFIRMED;
        }
    }
}
