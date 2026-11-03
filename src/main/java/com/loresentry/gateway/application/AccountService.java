package com.loresentry.gateway.application;

import java.util.UUID;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import com.loresentry.gateway.client.auth.AuthData;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

/**
 * 로그인한 사용자 자신의 계정을 auth에서 읽고 바꾼다. auth 호출 실패를 브라우저용 오류(AuthOperationFailure)로 바꾼다.
 *
 * <p>실패하면 어느 작업이 어떤 code로 실패했는지 warn 한 줄을 남긴다. 표시 이름·이메일·언어·유입 경로는 남기지 않는다.
 */
@Service
public class AccountService {
    public record Account(UUID id,String displayName,String email,boolean onboardingCompleted,String locale) {}
    private final AuthApiClient client;
    public AccountService(AuthApiClient client){this.client=client;}
    public Account get(UUID user) {
        try {return result(client.account(user));}
        catch(AuthCallFailure failure){AppLogger.current().warn("account lookup failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public Account update(UUID user,String displayName) {
        try {return result(client.updateAccount(user,displayName));}
        catch(AuthCallFailure failure){AppLogger.current().warn("account update failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public Account updateLocale(UUID user,String locale) {
        try {return result(client.updateLocale(user,locale));}
        catch(AuthCallFailure failure){AppLogger.current().warn("account locale update failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public void completeOnboarding(UUID user) {
        try {client.completeOnboarding(user);}
        catch(AuthCallFailure failure){AppLogger.current().warn("account onboarding completion failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public void recordAcquisition(UUID user,AuthData.Acquisition acquisition) {
        try {client.recordAcquisition(user,acquisition);}
        catch(AuthCallFailure failure){AppLogger.current().warn("account acquisition record failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    private static Account result(AuthData.Account account){return new Account(account.id(),account.displayName(),account.email(),account.onboardingCompleted(),account.locale());}
    private static AuthOperationFailure failure(AuthCallFailure failure) {
        if(failure.kind()==AuthCallFailure.Kind.CONTRACT) return new AuthOperationFailure(failure.status(),failure.code(),failure.nextAction());
        if(failure.kind()==AuthCallFailure.Kind.UNAVAILABLE) return new AuthOperationFailure(503,"ACCOUNT_UNAVAILABLE","RETRY_LATER");
        return new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","NONE");
    }
}
