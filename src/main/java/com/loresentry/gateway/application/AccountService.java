package com.loresentry.gateway.application;

import java.util.UUID;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import com.loresentry.gateway.client.auth.AuthData;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    public record Account(UUID id,String displayName,String email) {}
    private final AuthApiClient client;
    public AccountService(AuthApiClient client){this.client=client;}
    public Account get(UUID user) {
        try {return result(client.account(user));}catch(AuthCallFailure failure){throw failure(failure);}
    }
    public Account update(UUID user,String displayName) {
        try {return result(client.updateAccount(user,displayName));}catch(AuthCallFailure failure){throw failure(failure);}
    }
    private static Account result(AuthData.Account account){return new Account(account.id(),account.displayName(),account.email());}
    private static AuthOperationFailure failure(AuthCallFailure failure) {
        if(failure.kind()==AuthCallFailure.Kind.CONTRACT) return new AuthOperationFailure(failure.status(),failure.code(),failure.nextAction());
        if(failure.kind()==AuthCallFailure.Kind.UNAVAILABLE) return new AuthOperationFailure(503,"ACCOUNT_UNAVAILABLE","RETRY_LATER");
        return new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","NONE");
    }
}
