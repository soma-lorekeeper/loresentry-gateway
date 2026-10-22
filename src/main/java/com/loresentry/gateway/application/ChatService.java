package com.loresentry.gateway.application;

import java.util.UUID;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.ChatStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
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
        if (!allowlist.allows(userId)) throw new ChatNotEnabled();
    }

    private ChatData.Profile profile(UUID userId) {
        try {
            var account = accounts.get(userId);
            return new ChatData.Profile(account.locale(), account.displayName());
        } catch (RuntimeException failure) {
            log.warn("Chat request continues without a profile: {}", failure instanceof AuthOperationFailure auth ? auth.code() : failure.getClass().getSimpleName());
            return null;
        }
    }
}
