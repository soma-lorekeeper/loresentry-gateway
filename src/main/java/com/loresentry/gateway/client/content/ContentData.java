package com.loresentry.gateway.client.content;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Content wire DTOs. Never serialized directly by the web layer. */
public final class ContentData {
    private ContentData() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LastFile(UUID id, String title) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Project(UUID id, String name, String description, @JsonProperty("last_worked_at") OffsetDateTime lastWorkedAt, @JsonProperty("trashed_at") OffsetDateTime trashedAt, @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("last_file") LastFile lastFile) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Projects(List<Project> projects) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Folder(String code, String name, Integer position) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Episode(UUID id, String name, String rank) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Document(UUID id, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, String rank, Boolean locked, @JsonProperty("char_count") Integer charCount, @JsonProperty("revision_no") Long revisionNo, @JsonProperty("trashed_at") OffsetDateTime trashedAt, @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Tree(List<Folder> folders, List<Episode> episodes, List<Document> documents) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrashEntry(UUID id, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_name") String episodeName, @JsonProperty("trashed_at") OffsetDateTime trashedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Trash(List<TrashEntry> files) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TextProperty(String key, String value) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    /** {@code description} 은 대상 문서가 아니라 <b>이 연결</b>의 설명이다. */
    public record Relation(@JsonProperty("relation_key") String relationKey, @JsonProperty("target_document_id") UUID targetDocumentId, String description) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(String title, tools.jackson.databind.JsonNode body, @JsonProperty("legacy_body_md") String legacyBodyMd, List<TextProperty> properties, List<Relation> relations) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Content(UUID id, @JsonProperty("project_id") UUID projectId, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, tools.jackson.databind.JsonNode body, @JsonProperty("legacy_body_md") String legacyBodyMd, List<TextProperty> properties, List<Relation> relations, Boolean locked, @JsonProperty("char_count") Integer charCount, @JsonProperty("revision_no") Long revisionNo, @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Version(UUID id, @JsonProperty("file_id") UUID fileId, String kind, String label, @JsonProperty("source_revision_no") Long sourceRevisionNo, @JsonProperty("created_at") OffsetDateTime createdAt, Snapshot snapshot) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Versions(List<Version> versions) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snippet(String before, String match, String after) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hit(@JsonProperty("file_id") UUID fileId, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_name") String episodeName, Snippet snippet, @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hits(List<Hit> hits) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProjectInput(String name, String description) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileCreate(String kind, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Rename(String title) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Move(@JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, @JsonProperty("before_file_id") UUID beforeFileId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Lock(Boolean locked) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NamedVersion(String label) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Memo(UUID id, @JsonProperty("project_id") UUID projectId, String scope, @JsonProperty("document_id") UUID documentId, String title, String body, @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Memos(List<Memo> memos) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoCreate(String scope, @JsonProperty("document_id") UUID documentId, String title, String body) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoUpdate(String title, String body) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Favorites(@JsonProperty("file_ids") List<UUID> fileIds) {}
    /** 화면이 만든 레이아웃 JSON. BFF 도 Content 처럼 이 구조를 해석하지 않는다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkspaceState(tools.jackson.databind.JsonNode layout) {}
    public record GraphNode(UUID id, String title, @JsonProperty("folder_code") String folderCode, String description) {}
    public record GraphEdge(UUID id, UUID source, UUID target, @JsonProperty("relation_key") String relationKey, String description, String origin) {}
    public record GraphEpisode(UUID id, String name, @JsonProperty("document_ids") List<UUID> documentIds) {}
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges, List<GraphEpisode> episodes) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImageTicketRequest(@JsonProperty("file_name") String fileName, @JsonProperty("content_type") String contentType, @JsonProperty("size_bytes") Long sizeBytes) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImageTicket(@JsonProperty("image_id") UUID imageId, String key, @JsonProperty("upload_url") String uploadUrl, String method, java.util.Map<String, String> headers, @JsonProperty("expires_at") java.time.Instant expiresAt, @JsonProperty("public_url") String publicUrl) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Image(@JsonProperty("image_id") UUID imageId, @JsonProperty("project_id") UUID projectId, @JsonProperty("file_name") String fileName, String key, @JsonProperty("content_type") String contentType, @JsonProperty("size_bytes") Long sizeBytes, String status, @JsonProperty("public_url") String publicUrl, @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("committed_at") OffsetDateTime committedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FeedbackInput(String category, String message, String page, String client) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FeedbackCreated(UUID id, @JsonProperty("created_at") OffsetDateTime createdAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WithdrawalSurvey(List<String> reasons, String comment) {
        @Override public String toString(){return "WithdrawalSurvey[redacted]";}
    }

    // ── 그래프 최신화(/projects/{id}/graph-refresh) ──
    /** 최신화 요청 결과. 바뀐 문서가 없으면(NO_CHANGES) request_id 는 null 이고 files 는 비어 있다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshStart(@JsonProperty("request_id") UUID requestId, String status, List<RefreshFile> files) {}
    /** 이번 요청에 실린 문서와 그 revision. 최신화가 끝날 때까지 잠긴다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshFile(@JsonProperty("file_id") UUID fileId, @JsonProperty("revision_no") Long revisionNo) {}
    /** 실패한 최신화의 원인. message 는 null 일 수 있다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshError(String code, String message) {}
    /** 열린 작업본 수. JSON 의 {@code new} 는 Java 예약어라 필드 이름만 created 다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftCounts(Long total, Long matched, @JsonProperty("new") Long created) {}
    /** 최신화 하나의 상태. 화면은 GENERATING 인 동안 이것을 다시 읽는다(폴링). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshSummary(@JsonProperty("request_id") UUID requestId, String status, @JsonProperty("started_at") OffsetDateTime startedAt, @JsonProperty("ready_at") OffsetDateTime readyAt, @JsonProperty("completed_at") OffsetDateTime completedAt, RefreshError error, @JsonProperty("draft_counts") DraftCounts draftCounts) {}
    /** 검토 화면이 읽는 최신화 하나와 그 작업본 전부. 앞의 필드는 RefreshSummary 와 같다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshDetail(@JsonProperty("request_id") UUID requestId, String status, @JsonProperty("started_at") OffsetDateTime startedAt, @JsonProperty("ready_at") OffsetDateTime readyAt, @JsonProperty("completed_at") OffsetDateTime completedAt, RefreshError error, @JsonProperty("draft_counts") DraftCounts draftCounts, List<RefreshDraft> drafts) {}
    /** EDIT 작업본이 고치는 기존 문서. 이름은 지금 문서의 것이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshDocument(UUID id, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("revision_no") Long revisionNo) {}
    /** NEW 작업본이 만들 새 설정 문서 제안. candidate_id 는 {@code new:...} 모양이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshCandidate(@JsonProperty("candidate_id") String candidateId, @JsonProperty("folder_code") String folderCode, String name) {}
    /** 작업본의 관계 한 줄. 대상은 기존 문서(target_document_id)나 새 문서 제안(target_candidate_id) 중 하나다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftRelation(@JsonProperty("relation_key") String relationKey, @JsonProperty("target_document_id") UUID targetDocumentId, @JsonProperty("target_candidate_id") String targetCandidateId, String description) {}
    /** 작업본 한 쪽(left·right). Snapshot 과 같은 모양이고 관계 대상만 새 문서 제안일 수 있다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftSnapshot(String title, tools.jackson.databind.JsonNode body, @JsonProperty("legacy_body_md") String legacyBodyMd, List<TextProperty> properties, List<DraftRelation> relations) {}
    /**
     * 검토 작업본 하나. left 는 지금 문서, right 는 AI 제안이며 둘이 같아지면 matched 다.
     * EDIT 는 document 가, NEW 는 candidate 가 채워진다. NEW 는 left·right 가 null 일 수 있다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshDraft(UUID id, String kind, @JsonProperty("draft_revision") Long draftRevision, Boolean matched, @JsonProperty("updated_at") OffsetDateTime updatedAt, RefreshDocument document, RefreshCandidate candidate, @JsonProperty("base_version_id") UUID baseVersionId, @JsonProperty("base_revision_no") Long baseRevisionNo, @JsonProperty("base_snapshot") Snapshot baseSnapshot, @JsonProperty("base_mismatch") Boolean baseMismatch, @JsonProperty("left_revision_no") Long leftRevisionNo, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {}
    /** 작업본 자동 저장 요청. draft_revision 은 마지막으로 읽은 값이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftSave(@JsonProperty("draft_revision") Long draftRevision, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {}
    /** 작업본 저장 결과. Content 가 정규형으로 맞춘 두 쪽을 돌려준다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SavedDraft(UUID id, @JsonProperty("draft_revision") Long draftRevision, Boolean matched, @JsonProperty("updated_at") OffsetDateTime updatedAt, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {}
    /** 확정으로 만든 새 설정 문서. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshCreated(@JsonProperty("draft_id") UUID draftId, @JsonProperty("candidate_id") String candidateId, @JsonProperty("file_id") UUID fileId) {}
    /** 확정으로 내용이나 관계가 바뀐 기존 문서. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshUpdated(@JsonProperty("draft_id") UUID draftId, @JsonProperty("file_id") UUID fileId, @JsonProperty("revision_no") Long revisionNo) {}
    /** 확정 결과. unchanged 는 바뀐 것이 없던 작업본 id 다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshConfirmed(@JsonProperty("request_id") UUID requestId, String status, List<RefreshCreated> created, List<RefreshUpdated> updated, List<UUID> unchanged) {}
    /** REFRESH_DRAFT_CONFLICT 오류의 current. 다른 탭·기기가 먼저 저장한 작업본의 지금 모습이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftConflict(@JsonProperty("draft_revision") Long draftRevision, Boolean matched, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {}
    /** REFRESH_DOCUMENTS_CHANGED 오류의 documents 한 줄. 작업본을 뜬 뒤 바뀌었거나 열 수 없는 문서다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChangedDocument(@JsonProperty("draft_id") UUID draftId, @JsonProperty("file_id") UUID fileId) {}
}
