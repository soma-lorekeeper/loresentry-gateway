package com.loresentry.gateway.application;

import com.loresentry.gateway.client.SupportedLocale;
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
 * <p>실패하면 어느 작업이 어떤 code로 실패했는지 warn 한 줄을 남긴다. 표시 이름·이메일은 남기지 않는다.
 * 언어는 허용 목록 안의 코드만, 유입 경로는 가려서 길이만 남긴다.
 */
@Service
public class AccountService {
    public record Account(UUID id,String displayName,String email,boolean onboardingCompleted,String locale) {}
    private final AuthApiClient client;
    public AccountService(AuthApiClient client){this.client=client;}
    public Account get(UUID user) {
        AppLogger.current().info("account get");
        try {return result(client.account(user));}
        catch(AuthCallFailure failure){AppLogger.current().warn("account lookup failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public Account update(UUID user,String displayName) {
        AppLogger.current().info("account update");
        try {return result(client.updateAccount(user,displayName));}
        catch(AuthCallFailure failure){AppLogger.current().warn("account update failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public Account updateLocale(UUID user,String locale) {
        AppLogger.current().info("account locale update",LogField.code("locale",locale,SupportedLocale.VALUES));
        try {return result(client.updateLocale(user,locale));}
        catch(AuthCallFailure failure){AppLogger.current().warn("account locale update failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public void completeOnboarding(UUID user) {
        AppLogger.current().info("account onboarding complete");
        try {client.completeOnboarding(user);}
        catch(AuthCallFailure failure){AppLogger.current().warn("account onboarding completion failed",LogField.errorType(failure.code()));throw failure(failure);}
    }
    public void recordAcquisition(UUID user,AuthData.Acquisition acquisition) {
        // 광고 클릭 ID 값(click_id)은 LOG_SCHEMA.md 4절 금지 항목이라 가려서도 남기지 않는다. click_id_type은 허용 목록이 없어 가린다
        AppLogger.current().info("account acquisition record",LogField.param("utm_source",acquisition==null?null:acquisition.utmSource()),
                LogField.param("utm_medium",acquisition==null?null:acquisition.utmMedium()),LogField.param("utm_campaign",acquisition==null?null:acquisition.utmCampaign()),
                LogField.param("utm_term",acquisition==null?null:acquisition.utmTerm()),LogField.param("utm_content",acquisition==null?null:acquisition.utmContent()),
                LogField.param("click_id_type",acquisition==null?null:acquisition.clickIdType()),LogField.param("referrer_host",acquisition==null?null:acquisition.referrerHost()),
                LogField.param("landing_path",acquisition==null?null:acquisition.landingPath()),LogField.param("landed_at",acquisition==null?null:acquisition.landedAt()));
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
