package com.loresentry.gateway.application;

import java.util.UUID;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.ChatStream;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ChatApiClient client;
    private final AccountService accounts;
    private final ChatAllowlist allowlist;

    public ChatService(ChatApiClient client, AccountService accounts, ChatAllowlist allowlist) {
        this.client = client; this.accounts = accounts; this.allowlist = allowlist;
    }
    public ChatData.Availability availability(UUID userId, UUID projectId) { enabled(userId); return client.availability(userId, projectId); }
    public ChatData.Sessions sessions(UUID userId, UUID projectId) { enabled(userId); return client.sessions(userId, projectId); }
    public ChatData.Session createSession(UUID userId, UUID projectId) { enabled(userId); return client.createSession(userId, projectId, profile(userId)); }
    public ChatData.SearchHits search(UUID userId, UUID projectId, String query) { enabled(userId); return client.search(userId, projectId, query); }
    public ChatData.Session renameSession(UUID userId, UUID sessionId, ChatData.Rename body) { enabled(userId); return client.renameSession(userId, sessionId, body); }
    public void deleteSession(UUID userId, UUID sessionId) { enabled(userId); client.deleteSession(userId, sessionId); }
    public ChatData.Usage usage(UUID userId) { enabled(userId); return client.usage(userId); }
    public ChatData.Messages messages(UUID userId, UUID sessionId) { enabled(userId); return client.messages(userId, sessionId); }
    public ChatStream send(UUID userId, UUID sessionId, ChatData.SendInput body) { enabled(userId); return client.send(userId, sessionId, body, profile(userId)); }
    public void cancel(UUID userId, UUID sessionId) { enabled(userId); client.cancel(userId, sessionId); }

    private void enabled(UUID userId) {
        // 허용 목록(loresentry.chat.allowed-users)에 없는 사용자다. user.id는 SessionFilter가 이미 줄에 붙였다
        if (!allowlist.allows(userId)) {
            AppLogger.current().warn("chat not enabled for user");
            throw new ChatNotEnabled();
        }
    }

    private ChatData.Profile profile(UUID userId) {
        try {
            var account = accounts.get(userId);
            return new ChatData.Profile(account.locale(), account.displayName());
        } catch (RuntimeException failure) {
            AppLogger.current().warn("chat request continues without a profile",
                    LogField.errorType(failure instanceof AuthOperationFailure auth ? auth.code() : failure.getClass().getSimpleName()));
            return null;
        }
    }
}
