package com.loresentry.gateway.application;

import java.util.Locale;
import java.util.UUID;
import com.loresentry.gateway.client.auth.AuthApiClient;
import com.loresentry.gateway.client.auth.AuthCallFailure;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentData;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

@Service
public class AccountDeletionService {
    private final AuthApiClient auth;
    private final ContentApiClient content;
    private final ChatApiClient chat;
    public AccountDeletionService(AuthApiClient auth,ContentApiClient content,ChatApiClient chat){this.auth=auth;this.content=content;this.chat=chat;}
    public void delete(UUID user,String confirmationEmail){AppLogger.current().info("account delete");delete(user,confirmationEmail,null);}
    /**
     * 탈퇴 순서: 확인 이메일 대조 → ai-chat 데이터 삭제 → auth 계정 삭제 → (답했으면) 탈퇴 설문 기록.
     * 단계마다 결과를 한 줄 남긴다. 어느 단계에서 멈췄는지 보이게 하기 위해서다. 이메일·설문 내용은 개인정보라 남기지 않는다.
     */
    public void delete(UUID user,String confirmationEmail,ContentData.WithdrawalSurvey survey) {
        var log=AppLogger.current();
        // 확인 이메일은 남기지 않는다. 설문은 이유 개수와 가린 의견 길이만 남는다
        log.info("account delete",LogField.param("survey_reasons",survey==null?null:survey.reasons()),
                LogField.param("survey_comment",survey==null?null:survey.comment()));
        String email;
        try {email=auth.account(user).email();}
        catch(AuthCallFailure failure){log.warn("account deletion stopped: account lookup failed",LogField.errorType(failure.code()));throw failure(failure);}
        if(email==null||!normalize(email).equals(normalize(confirmationEmail))) {
            log.warn("account deletion rejected: confirmation email mismatch");
            throw new AuthOperationFailure(400,"ACCOUNT_CONFIRMATION_MISMATCH","NONE");
        }
        log.info("account deletion confirmed");
        try {chat.deleteUserData(user);}
        catch(ChatCallFailure failure) {
            log.warn("account deletion stopped: ai-chat data was not deleted",LogField.errorType(failure.code()));
            if("UPSTREAM_INVALID_RESPONSE".equals(failure.code())) throw invalid();
            throw unavailable();
        }
        log.info("account ai-chat data deleted");
        try {auth.deleteAccount(user);}
        catch(AuthCallFailure failure) {
            // 이미 지워진 계정(재시도 등)은 성공으로 본다. 이때는 설문을 기록하지 않는다
            if(failure.kind()==AuthCallFailure.Kind.CONTRACT&&failure.status()==404&&"USER_NOT_FOUND".equals(failure.code())) {
                log.warn("account was already deleted in auth");return;
            }
            log.warn("account deletion stopped: auth account was not deleted",LogField.errorType(failure.code()));
            throw failure(failure);
        }
        log.info("account auth record deleted");
        record(user,survey);
    }
    private void record(UUID user,ContentData.WithdrawalSurvey survey) {
        if(!answered(survey)) return;
        try {content.recordWithdrawalSurvey(user,survey);AppLogger.current().info("withdrawal survey recorded");}
        catch(RuntimeException failure){AppLogger.current().warn("withdrawal survey was not recorded",LogField.errorType(failure.getClass().getSimpleName()));}
    }
    private static boolean answered(ContentData.WithdrawalSurvey survey) {
        if(survey==null) return false;
        boolean reasons=survey.reasons()!=null&&!survey.reasons().isEmpty();
        boolean comment=survey.comment()!=null&&!survey.comment().isBlank();
        return reasons||comment;
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
