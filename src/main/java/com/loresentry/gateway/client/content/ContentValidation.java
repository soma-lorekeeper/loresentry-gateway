package com.loresentry.gateway.client.content;

import java.util.List;
import java.util.Objects;
import static com.loresentry.gateway.client.content.ContentData.*;

/** Required fields are checked at the service boundary, including nested objects. */
final class ContentValidation {
    private ContentValidation() {}
    private static void required(Object... fields) {
        for (Object field : fields) Objects.requireNonNull(field);
    }
    private static void items(List<?> values) {
        Objects.requireNonNull(values);
        values.forEach(ContentValidation::validate);
    }
    static <T> T validate(T value) {
        Objects.requireNonNull(value);
        switch (value) {
            case LastFile v -> required(v.id(), v.title());
            case Project v -> { required(v.id(),v.name(),v.description(),v.lastWorkedAt(),v.createdAt()); if(v.lastFile()!=null) validate(v.lastFile()); }
            case Projects v -> items(v.projects());
            case Folder v -> required(v.code(),v.name(),v.position());
            case Episode v -> required(v.id(),v.name(),v.rank());
            case Document v -> { required(v.id(),v.title(),v.folderCode(),v.rank(),v.locked(),v.charCount(),v.revisionNo(),v.updatedAt()); nonnegative(v.charCount(),v.revisionNo()); }
            case Tree v -> { items(v.folders());items(v.episodes());items(v.documents()); }
            case TrashEntry v -> required(v.id(),v.title(),v.folderCode(),v.trashedAt());
            case Trash v -> items(v.files());
            case TextProperty v -> required(v.key(),v.value());
            case Relation v -> required(v.relationKey(),v.targetDocumentId());
            case Snapshot v -> { required(v.title(),v.bodyMd());items(v.properties());items(v.relations()); }
            case Content v -> { required(v.id(),v.projectId(),v.title(),v.folderCode(),v.bodyMd(),v.locked(),v.charCount(),v.revisionNo(),v.updatedAt());items(v.properties());items(v.relations());nonnegative(v.charCount(),v.revisionNo()); }
            case Version v -> { required(v.id(),v.fileId(),v.kind(),v.sourceRevisionNo(),v.createdAt());validate(v.snapshot()); }
            case Versions v -> items(v.versions());
            case Snippet v -> required(v.before(),v.match(),v.after());
            case Hit v -> { required(v.fileId(),v.title(),v.folderCode(),v.updatedAt());if (v.snippet()!=null) validate(v.snippet()); }
            case Hits v -> items(v.hits());
            case Memo v -> required(v.id(),v.projectId(),v.scope(),v.body(),v.createdAt(),v.updatedAt());
            case Memos v -> items(v.memos());
            case Favorites v -> { Objects.requireNonNull(v.fileIds()); v.fileIds().forEach(Objects::requireNonNull); }
            // layout 은 null 일 수 있다. 처음 여는 프로젝트는 복원할 것이 없다.
            case WorkspaceState v -> { }
            case ImageTicket v -> required(v.imageId(),v.key(),v.uploadUrl(),v.method(),v.headers(),v.expiresAt());
            case Image v -> required(v.imageId(),v.projectId(),v.key(),v.contentType(),v.sizeBytes(),v.status(),v.createdAt());
            default -> throw new IllegalArgumentException("Unsupported Content response");
        }
        return value;
    }
    private static void nonnegative(int chars, long revision) {
        if (chars < 0 || revision < 0) throw new IllegalArgumentException("Invalid counters");
    }
}
