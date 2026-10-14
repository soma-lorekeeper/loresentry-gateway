package com.loresentry.gateway.client.chat;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import static com.loresentry.gateway.client.chat.ChatData.*;

final class ChatValidation {
    private static final Set<String> STATUSES = Set.of("available", "too-large");
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final Set<String> INTENTS = Set.of("ask", "explain", "related", "flow");
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
            default -> throw new IllegalArgumentException("Unsupported ai-chat response");
        }
        return value;
    }
}
