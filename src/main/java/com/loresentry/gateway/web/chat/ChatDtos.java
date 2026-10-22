package com.loresentry.gateway.web.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.loresentry.gateway.client.chat.ChatData;

public final class ChatDtos {
    private ChatDtos() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Availability(String status, long size, long limit) {
        public static Availability from(ChatData.Availability value) { return new Availability(value.status(), value.size(), value.limit()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Session(UUID id, @JsonProperty("project_id") UUID projectId, String title, @JsonProperty("updated_at") OffsetDateTime updatedAt, String preview) {
        public static Session from(ChatData.Session value) { return new Session(value.id(), value.projectId(), value.title(), value.updatedAt(), value.preview()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sessions(List<Session> sessions) {
        public static Sessions from(ChatData.Sessions value) { return new Sessions(value.sessions().stream().map(Session::from).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snippet(String before, String match, String after) {
        public static Snippet from(ChatData.Snippet value) { return value == null ? null : new Snippet(value.before(), value.match(), value.after()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SearchHit(Session session, Snippet snippet) {
        public static SearchHit from(ChatData.SearchHit value) { return new SearchHit(Session.from(value.session()), Snippet.from(value.snippet())); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SearchHits(List<SearchHit> hits) {
        public static SearchHits from(ChatData.SearchHits value) { return new SearchHits(value.hits().stream().map(SearchHit::from).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Quote(@JsonProperty("file_id") UUID fileId, String title, @JsonProperty("doc_type") String docType, String text) {
        public static Quote from(ChatData.Quote value) { return value == null ? null : new Quote(value.fileId(), value.title(), value.docType(), value.text()); }
        public ChatData.Quote internal() { return new ChatData.Quote(fileId, title, docType, text); }
    }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = DocumentCitation.class, name = "document"), @JsonSubTypes.Type(value = MemoCitation.class, name = "memo")})
    public sealed interface Citation permits DocumentCitation, MemoCitation {
        static Citation from(ChatData.Citation value) {
            return switch (value) {
                case ChatData.DocumentCitation v -> new DocumentCitation(v.fileId(), v.title(), v.docType(), v.quote());
                case ChatData.MemoCitation v -> new MemoCitation(v.memoId(), v.title(), v.quote());
            };
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DocumentCitation(@JsonProperty("file_id") UUID fileId, String title, @JsonProperty("doc_type") String docType, String quote) implements Citation {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoCitation(@JsonProperty("memo_id") UUID memoId, String title, String quote) implements Citation {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Message(UUID id, @JsonProperty("session_id") UUID sessionId, String role, String content, String intent, Quote quote, List<Citation> citations, @JsonProperty("created_at") OffsetDateTime createdAt) {
        public static Message from(ChatData.Message value) {
            return new Message(value.id(), value.sessionId(), value.role(), value.content(), value.intent(), Quote.from(value.quote()),
                    value.citations().stream().map(Citation::from).toList(), value.createdAt());
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Messages(List<Message> messages, boolean generating) {
        public static Messages from(ChatData.Messages value) { return new Messages(value.messages().stream().map(Message::from).toList(), value.generating()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Usage(int used, int limit, @JsonProperty("resets_at") OffsetDateTime resetsAt) {
        public static Usage from(ChatData.Usage value) { return new Usage(value.used(), value.limit(), value.resetsAt()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Rename(String title) {
        public ChatData.Rename internal() { return new ChatData.Rename(title); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SendInput(String content, String intent, Quote quote, @JsonProperty("focus_file_id") UUID focusFileId, UUID replaces, @JsonProperty("client_message_id") UUID clientMessageId) {
        public ChatData.SendInput internal() { return new ChatData.SendInput(content, intent, quote == null ? null : quote.internal(), focusFileId, replaces, clientMessageId); }
        @Override public String toString() { return "SendInput[redacted]"; }
    }
}
