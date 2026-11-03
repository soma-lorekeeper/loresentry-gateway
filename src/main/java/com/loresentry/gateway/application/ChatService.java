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
    public ChatData.Availability availability(UUID userId, UUID projectId) { AppLogger.current().info("chat availability get", LogField.projectId(projectId)); enabled(userId); return client.availability(userId, projectId); }
    public ChatData.Sessions sessions(UUID userId, UUID projectId) { AppLogger.current().info("chat session list", LogField.projectId(projectId)); enabled(userId); return client.sessions(userId, projectId); }
    public ChatData.Session createSession(UUID userId, UUID projectId) {
        AppLogger.current().info("chat session create", LogField.projectId(projectId));
        enabled(userId);
        var created = client.createSession(userId, projectId, profile(userId));
        // 새로 만든 대화 세션은 결과 줄의 전용 필드(chat.session_id)로 남긴다
        AppLogger.current().info("chat session create completed", LogField.projectId(projectId), LogField.chatSessionId(created == null ? null : created.id()));
        return created;
    }
    public ChatData.SearchHits search(UUID userId, UUID projectId, String query) { AppLogger.current().info("chat search", LogField.projectId(projectId), LogField.param("query", query)); enabled(userId); return client.search(userId, projectId, query); }
    public ChatData.Session renameSession(UUID userId, UUID sessionId, ChatData.Rename body) {
        AppLogger.current().info("chat session rename", LogField.chatSessionId(sessionId), LogField.param("title", body == null ? null : body.title()));
        enabled(userId);
        return client.renameSession(userId, sessionId, body);
    }
    public void deleteSession(UUID userId, UUID sessionId) { AppLogger.current().info("chat session delete", LogField.chatSessionId(sessionId)); enabled(userId); client.deleteSession(userId, sessionId); }
    public ChatData.Usage usage(UUID userId) { AppLogger.current().info("chat usage get"); enabled(userId); return client.usage(userId); }
    public ChatData.Messages messages(UUID userId, UUID sessionId) { AppLogger.current().info("chat message list", LogField.chatSessionId(sessionId)); enabled(userId); return client.messages(userId, sessionId); }
    public ChatStream send(UUID userId, UUID sessionId, ChatData.SendInput body) {
        // 인용한 파일·보고 있던 파일·다시 생성할 메시지는 대상(대화 세션)이 아니라 참조라 역할 이름을 붙인 param이다
        AppLogger.current().info("chat message send", LogField.chatSessionId(sessionId), LogField.param("content", body == null ? null : body.content()),
                LogField.code("intent", body == null ? null : body.intent(), ChatData.INTENTS),
                LogField.param("quote_file_id", body == null || body.quote() == null ? null : body.quote().fileId()),
                LogField.param("quote_text", body == null || body.quote() == null ? null : body.quote().text()), LogField.param("focus_file_id", body == null ? null : body.focusFileId()),
                LogField.param("replaced_message_id", body == null ? null : body.replaces()), LogField.param("client_message_id", body == null ? null : body.clientMessageId()));
        enabled(userId);
        return client.send(userId, sessionId, body, profile(userId));
    }
    public void cancel(UUID userId, UUID sessionId) { AppLogger.current().info("chat answer cancel", LogField.chatSessionId(sessionId)); enabled(userId); client.cancel(userId, sessionId); }

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
                    LogField.errorType(failure instanceof AuthOperationFailure auth ? auth.code() : failure.getClass().getName()));
            return null;
        }
    }
}
