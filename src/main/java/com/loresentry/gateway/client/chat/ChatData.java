package com.loresentry.gateway.client.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

public final class ChatData {
    private ChatData() {}
    public record Profile(String locale, String displayName) {
        @Override public String toString() { return "Profile[redacted]"; }
    }
    public record Availability(String status, Long size, Long limit) {}
    public record Session(UUID id, @JsonProperty("project_id") UUID projectId, String title, @JsonProperty("updated_at") OffsetDateTime updatedAt, String preview) {}
    public record Sessions(List<Session> sessions) {}
    public record Snippet(String before, String match, String after) {}
    public record SearchHit(Session session, Snippet snippet) {}
    public record SearchHits(List<SearchHit> hits) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Quote(@JsonProperty("file_id") UUID fileId, String title, @JsonProperty("doc_type") String docType, String text) {}
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = DocumentCitation.class, name = "document"), @JsonSubTypes.Type(value = MemoCitation.class, name = "memo")})
    public sealed interface Citation permits DocumentCitation, MemoCitation {}
    public record DocumentCitation(@JsonProperty("file_id") UUID fileId, String title, @JsonProperty("doc_type") String docType, String quote) implements Citation {}
    public record MemoCitation(@JsonProperty("memo_id") UUID memoId, String title, String quote) implements Citation {}
    public record Message(UUID id, @JsonProperty("session_id") UUID sessionId, String role, String content, String intent, Quote quote, List<Citation> citations, @JsonProperty("created_at") OffsetDateTime createdAt) {}
    public record Messages(List<Message> messages, Boolean generating) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Rename(String title) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SendInput(String content, String intent, Quote quote, @JsonProperty("focus_file_id") UUID focusFileId, UUID replaces, @JsonProperty("client_message_id") UUID clientMessageId) {
        @Override public String toString() { return "SendInput[redacted]"; }
    }
}
