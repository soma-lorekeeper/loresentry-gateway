package com.loresentry.gateway.application;

import java.util.UUID;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.SettingCheckData;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

@Service
public class SettingCheckService {
    private final ChatApiClient client;
    private final AccountService accounts;
    private final ChatAllowlist allowlist;

    public SettingCheckService(ChatApiClient client, AccountService accounts, ChatAllowlist allowlist) {
        this.client = client; this.accounts = accounts; this.allowlist = allowlist;
    }
    public SettingCheckData.Run start(UUID userId, UUID projectId, SettingCheckData.Start body) { enabled(userId); return client.startSettingCheck(userId, projectId, body, locale(userId)); }
    public SettingCheckData.Summary summary(UUID userId, UUID projectId) { enabled(userId); return client.settingCheckSummary(userId, projectId); }
    public SettingCheckData.Runs runs(UUID userId, UUID projectId, UUID documentId) { enabled(userId); return client.settingChecks(userId, projectId, documentId); }
    public SettingCheckData.Decisions verdicts(UUID userId, UUID projectId) { enabled(userId); return client.settingCheckVerdicts(userId, projectId); }
    public SettingCheckData.Run run(UUID userId, UUID runId) { enabled(userId); return client.settingCheck(userId, runId); }
    public void cancel(UUID userId, UUID runId) { enabled(userId); client.cancelSettingCheck(userId, runId); }
    public SettingCheckData.Finding setVerdict(UUID userId, UUID findingId, SettingCheckData.Verdict body) { enabled(userId); return client.setSettingCheckVerdict(userId, findingId, body); }
    public SettingCheckData.Finding clearVerdict(UUID userId, UUID findingId) { enabled(userId); return client.clearSettingCheckVerdict(userId, findingId); }

    private void enabled(UUID userId) {
        // 허용 목록(loresentry.chat.allowed-users)에 없는 사용자다. user.id는 SessionFilter가 이미 줄에 붙였다
        if (!allowlist.allows(userId)) {
            AppLogger.current().warn("setting check not enabled for user");
            throw new ChatNotEnabled();
        }
    }

    private ChatData.Profile locale(UUID userId) {
        try {
            return new ChatData.Profile(accounts.get(userId).locale(), null);
        } catch (RuntimeException failure) {
            AppLogger.current().warn("setting check starts without a locale",
                    LogField.errorType(failure instanceof AuthOperationFailure auth ? auth.code() : failure.getClass().getSimpleName()));
            return null;
        }
    }
}
