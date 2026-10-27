package com.loresentry.gateway.client.chat;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import static com.loresentry.gateway.client.chat.ChatData.*;
import static com.loresentry.gateway.client.chat.SettingCheckData.*;

final class ChatValidation {
    private static final Set<String> STATUSES = Set.of("available", "too-large");
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final Set<String> INTENTS = Set.of("ask", "explain", "related", "flow");
    private static final Set<String> RUN_STATUSES = Set.of("RUNNING", "DONE", "FAILED", "CANCELLED");
    private static final Set<String> RUN_STAGES = Set.of("READING", "FINDING", "JUDGING");
    private static final Set<String> FINDING_KINDS = Set.of("SETTING", "MANUSCRIPT");
    private static final Set<String> CONFIDENCES = Set.of("HIGH", "LOW");
    private static final Set<String> VERDICTS = Set.of("INTENDED", "NOT_CONFLICT");
    private ChatValidation() {}
    private static void required(Object... fields) {
        for (Object field : fields) Objects.requireNonNull(field);
    }
    private static void items(List<?> values) {
        Objects.requireNonNull(values);
        values.forEach(ChatValidation::validate);
    }
    private static void member(Set<String> allowed, String value) {
        if (!allowed.contains(value)) throw new IllegalArgumentException("Unexpected value");
    }
    private static void counts(Number... values) {
        for (Number value : values) if (Objects.requireNonNull(value).longValue() < 0) throw new IllegalArgumentException("Invalid count");
    }
    static <T> T validate(T value) {
        Objects.requireNonNull(value);
        switch (value) {
            case Availability v -> { required(v.status(), v.size(), v.limit()); member(STATUSES, v.status()); if (v.size() < 0 || v.limit() < 0) throw new IllegalArgumentException("Invalid size"); }
            case Session v -> required(v.id(), v.projectId(), v.title(), v.updatedAt(), v.preview());
            case Sessions v -> items(v.sessions());
            case Snippet v -> required(v.before(), v.match(), v.after());
            case SearchHit v -> { validate(v.session()); if (v.snippet() != null) validate(v.snippet()); }
            case SearchHits v -> items(v.hits());
            case Quote v -> required(v.fileId(), v.title(), v.docType(), v.text());
            case DocumentCitation v -> required(v.fileId(), v.title(), v.docType());
            case MemoCitation v -> required(v.memoId(), v.title());
            case Message v -> {
                required(v.id(), v.sessionId(), v.role(), v.content(), v.createdAt());
                member(ROLES, v.role());
                if (v.intent() != null) member(INTENTS, v.intent());
                if (v.quote() != null) validate(v.quote());
                items(v.citations());
            }
            case Messages v -> { items(v.messages()); required(v.generating()); }
            case Usage v -> { required(v.used(), v.limit(), v.resetsAt()); if (v.used() < 0 || v.limit() < 0) throw new IllegalArgumentException("Invalid usage"); }
            case Read v -> counts(v.manuscripts(), v.settings(), v.coreFacts());
            case Counts v -> counts(v.open(), v.intended(), v.notConflict());
            case Source v -> required(v.documentId(), v.title(), v.docType(), v.quote());
            case Finding v -> {
                required(v.id(), v.kind(), v.core(), v.confidence(), v.summary(), v.direction(), v.quote());
                member(FINDING_KINDS, v.kind());
                member(CONFIDENCES, v.confidence());
                if (v.verdict() != null) member(VERDICTS, v.verdict());
                validate(v.source());
            }
            case Run v -> {
                required(v.id(), v.projectId(), v.documentId(), v.documentTitle(), v.status(), v.createdAt());
                counts(v.revisionNo());
                member(RUN_STATUSES, v.status());
                if (v.stage() != null) member(RUN_STAGES, v.stage());
                validate(v.read());
                validate(v.counts());
                if (v.findings() != null) items(v.findings());
            }
            case Chapter v -> { required(v.documentId()); validate(v.latest()); }
            case Summary v -> { if (v.running() != null) validate(v.running()); items(v.chapters()); }
            case Runs v -> items(v.runs());
            default -> throw new IllegalArgumentException("Unsupported ai-chat response");
        }
        return value;
    }
}
