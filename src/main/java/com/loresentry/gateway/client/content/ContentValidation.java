package com.loresentry.gateway.client.content;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.OffsetDateTime;
import static com.loresentry.gateway.client.content.ContentData.*;

/** Required fields are checked at the service boundary, including nested objects. */
final class ContentValidation {
    private ContentValidation() {}
    private static void required(Object... fields) {
        for (Object field : fields) Objects.requireNonNull(field);
    }
    /** 둘 다 비어 있으면 본문이 없는 응답이다. 그것은 화면이 다룰 수 없다. */
    private static void body(tools.jackson.databind.JsonNode body, String legacyBodyMd) {
        if (body == null && legacyBodyMd == null) throw new NullPointerException("body");
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
            // description 은 비어 있을 수 있다. 적지 않은 관계가 대부분이다.
            case Relation v -> required(v.relationKey(),v.targetDocumentId());
            // 본문은 body 나 legacy_body_md 중 하나다. 변환 전 문서는 Markdown 만 온다.
            case Snapshot v -> { required(v.title());body(v.body(),v.legacyBodyMd());items(v.properties());items(v.relations()); }
            case Content v -> { required(v.id(),v.projectId(),v.title(),v.folderCode(),v.locked(),v.charCount(),v.revisionNo(),v.updatedAt());body(v.body(),v.legacyBodyMd());items(v.properties());items(v.relations());nonnegative(v.charCount(),v.revisionNo()); }
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
            case GraphNode v -> required(v.id(),v.title(),v.folderCode(),v.description());
            // origin 은 관계를 누가 만들었는지다. graph-rag 가 붙어도 이 모양은 바뀌지 않는다.
            case GraphEdge v -> required(v.id(),v.source(),v.target(),v.relationKey(),v.description(),v.origin());
            case GraphEpisode v -> { required(v.id(),v.name()); Objects.requireNonNull(v.documentIds()); v.documentIds().forEach(Objects::requireNonNull); }
            case Graph v -> { items(v.nodes()); items(v.edges()); items(v.episodes()); }
            case ImageTicket v -> required(v.imageId(),v.key(),v.uploadUrl(),v.method(),v.headers(),v.expiresAt());
            case Image v -> required(v.imageId(),v.projectId(),v.key(),v.contentType(),v.sizeBytes(),v.status(),v.createdAt());
            case FeedbackCreated v -> required(v.id(),v.createdAt());
            // ── 그래프 최신화 ──
            case RefreshFile v -> required(v.fileId(),v.revisionNo());
            // 바뀐 문서가 없으면(NO_CHANGES) 요청을 만들지 않아 request_id 가 null 이고 files 는 비어 있다.
            case RefreshStart v -> { oneOf(v.status(),START_STATUSES); if ("GENERATING".equals(v.status())) required(v.requestId()); items(v.files()); }
            case RefreshError v -> required(v.code());
            case DraftCounts v -> required(v.total(),v.matched(),v.created());
            case RefreshSummary v -> run(v.requestId(),v.status(),v.startedAt(),v.error(),v.draftCounts());
            case RefreshDetail v -> { run(v.requestId(),v.status(),v.startedAt(),v.error(),v.draftCounts()); items(v.drafts()); }
            case RefreshDocument v -> required(v.id(),v.title(),v.folderCode(),v.revisionNo());
            case RefreshCandidate v -> required(v.candidateId(),v.folderCode(),v.name());
            // 대상은 기존 문서나 새 문서 제안 중 정확히 하나다. description 은 비어 있을 수 있다.
            case DraftRelation v -> { required(v.relationKey()); if ((v.targetDocumentId()==null)==(v.targetCandidateId()==null)) throw new IllegalArgumentException("Relation target"); }
            case DraftSnapshot v -> { required(v.title());body(v.body(),v.legacyBodyMd());items(v.properties());items(v.relations()); }
            // EDIT 는 고칠 기존 문서(document), NEW 는 새 문서 제안(candidate)을 가리킨다. 스냅샷은 없을 수 있다.
            case RefreshDraft v -> {
                required(v.id(),v.kind(),v.draftRevision(),v.matched(),v.updatedAt(),v.baseMismatch());
                switch (v.kind()) {
                    case "EDIT" -> validate(v.document());
                    case "NEW" -> validate(v.candidate());
                    default -> throw new IllegalArgumentException("Unknown draft kind");
                }
                optional(v.baseSnapshot(),v.leftSnapshot(),v.rightSnapshot());
            }
            case SavedDraft v -> { required(v.id(),v.draftRevision(),v.matched(),v.updatedAt()); optional(v.leftSnapshot(),v.rightSnapshot()); }
            case RefreshCreated v -> required(v.draftId(),v.candidateId(),v.fileId());
            case RefreshUpdated v -> required(v.draftId(),v.fileId(),v.revisionNo());
            case RefreshConfirmed v -> { required(v.requestId()); oneOf(v.status(),List.of("APPLIED")); items(v.created()); items(v.updated()); Objects.requireNonNull(v.unchanged()); v.unchanged().forEach(Objects::requireNonNull); }
            case DraftConflict v -> { required(v.draftRevision(),v.matched()); optional(v.leftSnapshot(),v.rightSnapshot()); }
            case ChangedDocument v -> required(v.draftId(),v.fileId());
            // 최신화 오류의 상세 값. code 에 맞는 값이 있는지는 client 가 보고, 여기서는 온 값의 모양만 본다.
            case ContentCallFailure.Details v -> {
                if (v.current()!=null) validate(v.current());
                if (v.drafts()!=null) v.drafts().forEach(Objects::requireNonNull);
                if (v.documents()!=null) items(v.documents());
                // problem 은 code 와 문자열 값들이다. code 별 칸(one·other·target·draft_id)은 해석하지 않고 옮긴다.
                if (v.problems()!=null) v.problems().forEach(problem -> { Objects.requireNonNull(problem.get("code")); problem.values().forEach(Objects::requireNonNull); });
            }
            default -> throw new IllegalArgumentException("Unsupported Content response");
        }
        return value;
    }
    /** 최신화 요청 결과의 status. 요약의 NO_CHANGE 와 철자가 다르다. */
    private static final List<String> START_STATUSES = List.of("GENERATING","NO_CHANGES");
    /** 최신화 요약·상세의 status. 화면은 이 값으로 다음 화면을 고른다. */
    private static final List<String> RUN_STATUSES = List.of("GENERATING","READY","NO_CHANGE","FAILED","APPLIED");
    private static void oneOf(String value, List<String> allowed) {
        if (!allowed.contains(Objects.requireNonNull(value))) throw new IllegalArgumentException("Unknown status");
    }
    /** null 이 허용된 값은 있을 때만 확인한다. */
    private static void optional(Object... values) {
        for (Object value : values) if (value != null) validate(value);
    }
    /** 최신화 요약과 상세가 함께 갖는 필드. error 는 실패했을 때만 있다. */
    private static void run(UUID requestId, String status, OffsetDateTime startedAt, RefreshError error, DraftCounts counts) {
        required(requestId,startedAt);
        oneOf(status,RUN_STATUSES);
        validate(counts);
        if (error!=null) validate(error);
    }
    private static void nonnegative(int chars, long revision) {
        if (chars < 0 || revision < 0) throw new IllegalArgumentException("Invalid counters");
    }
}
