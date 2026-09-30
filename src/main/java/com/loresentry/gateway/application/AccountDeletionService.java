package com.loresentry.gateway.application;

import java.util.Locale;
import java.util.UUID;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentCallFailure;
import org.springframework.stereotype.Service;

@Service
public class AccountDeletionService {
    private final AuthApiClient auth;
    private final ContentApiClient content;
    public AccountDeletionService(AuthApiClient auth,ContentApiClient content){this.auth=auth;this.content=content;}
    public void delete(UUID user,String confirmationEmail) {
        String email;
        try {email=auth.account(user).email();}catch(AuthCallFailure failure){throw failure(failure);}
        if(email==null||!normalize(email).equals(normalize(confirmationEmail)))
            throw new AuthOperationFailure(400,"ACCOUNT_CONFIRMATION_MISMATCH","NONE");
        try {content.deleteUserData(user);}
        catch(ContentCallFailure failure) {
            if("UPSTREAM_INVALID_RESPONSE".equals(failure.code())) throw invalid();
            throw unavailable();
        }
        try {auth.deleteAccount(user);}
        catch(AuthCallFailure failure) {
            if(failure.kind()==AuthCallFailure.Kind.CONTRACT&&failure.status()==404&&"USER_NOT_FOUND".equals(failure.code())) return;
            throw failure(failure);
        }
    }
    private static String normalize(String value){return value.trim().toLowerCase(Locale.ROOT);}
    private static AuthOperationFailure failure(AuthCallFailure failure) {
        if(failure.kind()==AuthCallFailure.Kind.INVALID_RESPONSE) return invalid();
        if(failure.kind()==AuthCallFailure.Kind.CONTRACT&&(failure.status()==401||failure.status()==404))
            return new AuthOperationFailure(failure.status(),failure.code(),failure.nextAction());
        return unavailable();
    }
    private static AuthOperationFailure invalid(){return new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","NONE");}
    private static AuthOperationFailure unavailable(){return new AuthOperationFailure(503,"ACCOUNT_DELETION_UNAVAILABLE","RETRY_LATER");}
}
