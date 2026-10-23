package com.loresentry.gateway.web.content;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.loresentry.gateway.client.content.ContentData;

/** Public DTOs keep the browser contract independent from Content responses. */
public final class ContentDtos {
    private ContentDtos() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LastFile(UUID id, String title) {
        public static LastFile from(ContentData.LastFile value) { return value == null ? null : new LastFile(value.id(), value.title()); }
        public ContentData.LastFile internal() { return new ContentData.LastFile(id, title); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Project(UUID id, String name, String description, @JsonProperty("last_worked_at") OffsetDateTime lastWorkedAt, @JsonProperty("trashed_at") OffsetDateTime trashedAt, @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("last_file") LastFile lastFile) {
        public static Project from(ContentData.Project value) { return value == null ? null : new Project(value.id(), value.name(), value.description(), value.lastWorkedAt(), value.trashedAt(), value.createdAt(), LastFile.from(value.lastFile())); }
        public ContentData.Project internal() { return new ContentData.Project(id, name, description, lastWorkedAt, trashedAt, createdAt, lastFile == null ? null : lastFile.internal()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Projects(List<Project> projects) {
        public static Projects from(ContentData.Projects value) { return value == null ? null : new Projects(value.projects() == null ? null : value.projects().stream().map(Project::from).toList()); }
        public ContentData.Projects internal() { return new ContentData.Projects(projects == null ? null : projects.stream().map(Project::internal).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Folder(String code, String name, Integer position) {
        public static Folder from(ContentData.Folder value) { return value == null ? null : new Folder(value.code(), value.name(), value.position()); }
        public ContentData.Folder internal() { return new ContentData.Folder(code, name, position); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Episode(UUID id, String name, String rank) {
        public static Episode from(ContentData.Episode value) { return value == null ? null : new Episode(value.id(), value.name(), value.rank()); }
        public ContentData.Episode internal() { return new ContentData.Episode(id, name, rank); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Document(UUID id, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, String rank, Boolean locked, @JsonProperty("refresh_locked") Boolean refreshLocked, @JsonProperty("char_count") Integer charCount, @JsonProperty("revision_no") Long revisionNo, @JsonProperty("trashed_at") OffsetDateTime trashedAt, @JsonProperty("updated_at") OffsetDateTime updatedAt) {
        public static Document from(ContentData.Document value) { return value == null ? null : new Document(value.id(), value.title(), value.folderCode(), value.episodeId(), value.rank(), value.locked(), value.refreshLocked(), value.charCount(), value.revisionNo(), value.trashedAt(), value.updatedAt()); }
        public ContentData.Document internal() { return new ContentData.Document(id, title, folderCode, episodeId, rank, locked, refreshLocked, charCount, revisionNo, trashedAt, updatedAt); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Tree(List<Folder> folders, List<Episode> episodes, List<Document> documents) {
        public static Tree from(ContentData.Tree value) { return value == null ? null : new Tree(value.folders() == null ? null : value.folders().stream().map(Folder::from).toList(), value.episodes() == null ? null : value.episodes().stream().map(Episode::from).toList(), value.documents() == null ? null : value.documents().stream().map(Document::from).toList()); }
        public ContentData.Tree internal() { return new ContentData.Tree(folders == null ? null : folders.stream().map(Folder::internal).toList(), episodes == null ? null : episodes.stream().map(Episode::internal).toList(), documents == null ? null : documents.stream().map(Document::internal).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrashEntry(UUID id, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_name") String episodeName, @JsonProperty("trashed_at") OffsetDateTime trashedAt) {
        public static TrashEntry from(ContentData.TrashEntry value) { return value == null ? null : new TrashEntry(value.id(), value.title(), value.folderCode(), value.episodeName(), value.trashedAt()); }
        public ContentData.TrashEntry internal() { return new ContentData.TrashEntry(id, title, folderCode, episodeName, trashedAt); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Trash(List<TrashEntry> files) {
        public static Trash from(ContentData.Trash value) { return value == null ? null : new Trash(value.files() == null ? null : value.files().stream().map(TrashEntry::from).toList()); }
        public ContentData.Trash internal() { return new ContentData.Trash(files == null ? null : files.stream().map(TrashEntry::internal).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TextProperty(String key, String value) {
        public static TextProperty from(ContentData.TextProperty value) { return value == null ? null : new TextProperty(value.key(), value.value()); }
        public ContentData.TextProperty internal() { return new ContentData.TextProperty(key, value); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Relation(@JsonProperty("relation_key") String relationKey, @JsonProperty("target_document_id") UUID targetDocumentId, String description) {
        public static Relation from(ContentData.Relation value) { return value == null ? null : new Relation(value.relationKey(), value.targetDocumentId(), value.description()); }
        public ContentData.Relation internal() { return new ContentData.Relation(relationKey, targetDocumentId, description); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(String title, tools.jackson.databind.JsonNode body, @JsonProperty("legacy_body_md") String legacyBodyMd, List<TextProperty> properties, List<Relation> relations) {
        public static Snapshot from(ContentData.Snapshot value) { return value == null ? null : new Snapshot(value.title(), value.body(), value.legacyBodyMd(), value.properties() == null ? null : value.properties().stream().map(TextProperty::from).toList(), value.relations() == null ? null : value.relations().stream().map(Relation::from).toList()); }
        public ContentData.Snapshot internal() { return new ContentData.Snapshot(title, body, legacyBodyMd, properties == null ? null : properties.stream().map(v -> v == null ? null : v.internal()).toList(), relations == null ? null : relations.stream().map(v -> v == null ? null : v.internal()).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Content(UUID id, @JsonProperty("project_id") UUID projectId, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, tools.jackson.databind.JsonNode body, @JsonProperty("legacy_body_md") String legacyBodyMd, List<TextProperty> properties, List<Relation> relations, Boolean locked, @JsonProperty("refresh_locked") Boolean refreshLocked, @JsonProperty("char_count") Integer charCount, @JsonProperty("revision_no") Long revisionNo, @JsonProperty("updated_at") OffsetDateTime updatedAt) {
        public static Content from(ContentData.Content value) { return value == null ? null : new Content(value.id(), value.projectId(), value.title(), value.folderCode(), value.episodeId(), value.body(), value.legacyBodyMd(), value.properties() == null ? null : value.properties().stream().map(TextProperty::from).toList(), value.relations() == null ? null : value.relations().stream().map(Relation::from).toList(), value.locked(), value.refreshLocked(), value.charCount(), value.revisionNo(), value.updatedAt()); }
        public ContentData.Content internal() { return new ContentData.Content(id, projectId, title, folderCode, episodeId, body, legacyBodyMd, properties == null ? null : properties.stream().map(v -> v == null ? null : v.internal()).toList(), relations == null ? null : relations.stream().map(v -> v == null ? null : v.internal()).toList(), locked, refreshLocked, charCount, revisionNo, updatedAt); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Version(UUID id, @JsonProperty("file_id") UUID fileId, String kind, String label, @JsonProperty("source_revision_no") Long sourceRevisionNo, @JsonProperty("created_at") OffsetDateTime createdAt, Snapshot snapshot) {
        public static Version from(ContentData.Version value) { return value == null ? null : new Version(value.id(), value.fileId(), value.kind(), value.label(), value.sourceRevisionNo(), value.createdAt(), Snapshot.from(value.snapshot())); }
        public ContentData.Version internal() { return new ContentData.Version(id, fileId, kind, label, sourceRevisionNo, createdAt, snapshot == null ? null : snapshot.internal()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Versions(List<Version> versions) {
        public static Versions from(ContentData.Versions value) { return value == null ? null : new Versions(value.versions() == null ? null : value.versions().stream().map(Version::from).toList()); }
        public ContentData.Versions internal() { return new ContentData.Versions(versions == null ? null : versions.stream().map(Version::internal).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snippet(String before, String match, String after) {
        public static Snippet from(ContentData.Snippet value) { return value == null ? null : new Snippet(value.before(), value.match(), value.after()); }
        public ContentData.Snippet internal() { return new ContentData.Snippet(before, match, after); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hit(@JsonProperty("file_id") UUID fileId, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_name") String episodeName, Snippet snippet, @JsonProperty("updated_at") OffsetDateTime updatedAt) {
        public static Hit from(ContentData.Hit value) { return value == null ? null : new Hit(value.fileId(), value.title(), value.folderCode(), value.episodeName(), Snippet.from(value.snippet()), value.updatedAt()); }
        public ContentData.Hit internal() { return new ContentData.Hit(fileId, title, folderCode, episodeName, snippet == null ? null : snippet.internal(), updatedAt); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hits(List<Hit> hits) {
        public static Hits from(ContentData.Hits value) { return value == null ? null : new Hits(value.hits() == null ? null : value.hits().stream().map(Hit::from).toList()); }
        public ContentData.Hits internal() { return new ContentData.Hits(hits == null ? null : hits.stream().map(Hit::internal).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProjectInput(String name, String description) {
        public static ProjectInput from(ContentData.ProjectInput value) { return value == null ? null : new ProjectInput(value.name(), value.description()); }
        public ContentData.ProjectInput internal() { return new ContentData.ProjectInput(name, description); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileCreate(String kind, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId) {
        public static FileCreate from(ContentData.FileCreate value) { return value == null ? null : new FileCreate(value.kind(), value.title(), value.folderCode(), value.episodeId()); }
        public ContentData.FileCreate internal() { return new ContentData.FileCreate(kind, title, folderCode, episodeId); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Rename(String title) {
        public static Rename from(ContentData.Rename value) { return value == null ? null : new Rename(value.title()); }
        public ContentData.Rename internal() { return new ContentData.Rename(title); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Move(@JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, @JsonProperty("before_file_id") UUID beforeFileId) {
        public static Move from(ContentData.Move value) { return value == null ? null : new Move(value.folderCode(), value.episodeId(), value.beforeFileId()); }
        public ContentData.Move internal() { return new ContentData.Move(folderCode, episodeId, beforeFileId); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Lock(Boolean locked) {
        public static Lock from(ContentData.Lock value) { return value == null ? null : new Lock(value.locked()); }
        public ContentData.Lock internal() { return new ContentData.Lock(locked); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NamedVersion(String label) {
        public static NamedVersion from(ContentData.NamedVersion value) { return value == null ? null : new NamedVersion(value.label()); }
        public ContentData.NamedVersion internal() { return new ContentData.NamedVersion(label); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Memo(UUID id, @JsonProperty("project_id") UUID projectId, String scope, @JsonProperty("document_id") UUID documentId, String title, String body, @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("updated_at") OffsetDateTime updatedAt) {
        public static Memo from(ContentData.Memo value) { return value == null ? null : new Memo(value.id(), value.projectId(), value.scope(), value.documentId(), value.title(), value.body(), value.createdAt(), value.updatedAt()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Memos(List<Memo> memos) {
        public static Memos from(ContentData.Memos value) { return value == null ? null : new Memos(value.memos().stream().map(Memo::from).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoCreate(String scope, @JsonProperty("document_id") UUID documentId, String title, String body) {
        public ContentData.MemoCreate internal() { return new ContentData.MemoCreate(scope, documentId, title, body); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoUpdate(String title, String body) {
        public ContentData.MemoUpdate internal() { return new ContentData.MemoUpdate(title, body); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Favorites(@JsonProperty("file_ids") List<UUID> fileIds) {
        public static Favorites from(ContentData.Favorites value) { return value == null ? null : new Favorites(value.fileIds()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GraphNode(UUID id, String title, @JsonProperty("folder_code") String folderCode, String description) {
        public static GraphNode from(ContentData.GraphNode v) { return v == null ? null : new GraphNode(v.id(), v.title(), v.folderCode(), v.description()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GraphEdge(UUID id, UUID source, UUID target, @JsonProperty("relation_key") String relationKey, String description, String origin) {
        public static GraphEdge from(ContentData.GraphEdge v) { return v == null ? null : new GraphEdge(v.id(), v.source(), v.target(), v.relationKey(), v.description(), v.origin()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GraphEpisode(UUID id, String name, @JsonProperty("document_ids") java.util.List<UUID> documentIds) {
        public static GraphEpisode from(ContentData.GraphEpisode v) { return v == null ? null : new GraphEpisode(v.id(), v.name(), v.documentIds()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Graph(java.util.List<GraphNode> nodes, java.util.List<GraphEdge> edges, java.util.List<GraphEpisode> episodes) {
        public static Graph from(ContentData.Graph v) {
            return v == null ? null : new Graph(
                    v.nodes().stream().map(GraphNode::from).toList(),
                    v.edges().stream().map(GraphEdge::from).toList(),
                    v.episodes().stream().map(GraphEpisode::from).toList());
        }
    }
    public record WorkspaceState(tools.jackson.databind.JsonNode layout) {
        public static WorkspaceState from(ContentData.WorkspaceState value) { return value == null ? null : new WorkspaceState(value.layout()); }
        public ContentData.WorkspaceState internal() { return new ContentData.WorkspaceState(layout); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImageTicketRequest(@JsonProperty("file_name") String fileName, @JsonProperty("content_type") String contentType, @JsonProperty("size_bytes") Long sizeBytes) {
        public ContentData.ImageTicketRequest internal() { return new ContentData.ImageTicketRequest(fileName, contentType, sizeBytes); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImageTicket(@JsonProperty("image_id") UUID imageId, String key, @JsonProperty("upload_url") String uploadUrl, String method, java.util.Map<String, String> headers, @JsonProperty("expires_at") java.time.Instant expiresAt, @JsonProperty("public_url") String publicUrl) {
        public static ImageTicket from(ContentData.ImageTicket value) { return value == null ? null : new ImageTicket(value.imageId(), value.key(), value.uploadUrl(), value.method(), value.headers(), value.expiresAt(), value.publicUrl()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Image(@JsonProperty("image_id") UUID imageId, @JsonProperty("project_id") UUID projectId, @JsonProperty("file_name") String fileName, String key, @JsonProperty("content_type") String contentType, @JsonProperty("size_bytes") Long sizeBytes, String status, @JsonProperty("public_url") String publicUrl, @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("committed_at") OffsetDateTime committedAt) {
        public static Image from(ContentData.Image value) { return value == null ? null : new Image(value.imageId(), value.projectId(), value.fileName(), value.key(), value.contentType(), value.sizeBytes(), value.status(), value.publicUrl(), value.createdAt(), value.committedAt()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FeedbackInput(String category, String message, String page, String client) {
        public ContentData.FeedbackInput internal() { return new ContentData.FeedbackInput(category, message, page, client); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FeedbackCreated(UUID id, @JsonProperty("created_at") OffsetDateTime createdAt) {
        public static FeedbackCreated from(ContentData.FeedbackCreated value) { return value == null ? null : new FeedbackCreated(value.id(), value.createdAt()); }
    }

    // ── 그래프 최신화. 필드 의미는 ContentData 의 같은 이름 레코드를 따른다. ──
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshFile(@JsonProperty("file_id") UUID fileId, @JsonProperty("revision_no") Long revisionNo) {
        public static RefreshFile from(ContentData.RefreshFile v) { return v == null ? null : new RefreshFile(v.fileId(), v.revisionNo()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshStart(@JsonProperty("request_id") UUID requestId, String status, List<RefreshFile> files) {
        public static RefreshStart from(ContentData.RefreshStart v) { return v == null ? null : new RefreshStart(v.requestId(), v.status(), v.files().stream().map(RefreshFile::from).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshError(String code, String message) {
        public static RefreshError from(ContentData.RefreshError v) { return v == null ? null : new RefreshError(v.code(), v.message()); }
    }
    /** JSON 의 {@code new} 는 Java 예약어라 필드 이름만 created 다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftCounts(Long total, Long matched, @JsonProperty("new") Long created) {
        public static DraftCounts from(ContentData.DraftCounts v) { return v == null ? null : new DraftCounts(v.total(), v.matched(), v.created()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshSummary(@JsonProperty("request_id") UUID requestId, String status, @JsonProperty("started_at") OffsetDateTime startedAt, @JsonProperty("ready_at") OffsetDateTime readyAt, @JsonProperty("completed_at") OffsetDateTime completedAt, RefreshError error, @JsonProperty("draft_counts") DraftCounts draftCounts) {
        public static RefreshSummary from(ContentData.RefreshSummary v) { return v == null ? null : new RefreshSummary(v.requestId(), v.status(), v.startedAt(), v.readyAt(), v.completedAt(), RefreshError.from(v.error()), DraftCounts.from(v.draftCounts())); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshDetail(@JsonProperty("request_id") UUID requestId, String status, @JsonProperty("started_at") OffsetDateTime startedAt, @JsonProperty("ready_at") OffsetDateTime readyAt, @JsonProperty("completed_at") OffsetDateTime completedAt, RefreshError error, @JsonProperty("draft_counts") DraftCounts draftCounts, List<RefreshDraft> drafts) {
        public static RefreshDetail from(ContentData.RefreshDetail v) { return v == null ? null : new RefreshDetail(v.requestId(), v.status(), v.startedAt(), v.readyAt(), v.completedAt(), RefreshError.from(v.error()), DraftCounts.from(v.draftCounts()), v.drafts().stream().map(RefreshDraft::from).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshDocument(UUID id, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("revision_no") Long revisionNo) {
        public static RefreshDocument from(ContentData.RefreshDocument v) { return v == null ? null : new RefreshDocument(v.id(), v.title(), v.folderCode(), v.revisionNo()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshCandidate(@JsonProperty("candidate_id") String candidateId, @JsonProperty("folder_code") String folderCode, String name) {
        public static RefreshCandidate from(ContentData.RefreshCandidate v) { return v == null ? null : new RefreshCandidate(v.candidateId(), v.folderCode(), v.name()); }
    }
    /** 작업본 저장 요청에도 쓰인다. relation_key 는 Content 가 대상 분류로 다시 정하므로 보내지 않아도 된다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftRelation(@JsonProperty("relation_key") String relationKey, @JsonProperty("target_document_id") UUID targetDocumentId, @JsonProperty("target_candidate_id") String targetCandidateId, String description) {
        public static DraftRelation from(ContentData.DraftRelation v) { return v == null ? null : new DraftRelation(v.relationKey(), v.targetDocumentId(), v.targetCandidateId(), v.description()); }
        public ContentData.DraftRelation internal() { return new ContentData.DraftRelation(relationKey, targetDocumentId, targetCandidateId, description); }
    }
    /** 작업본 한 쪽. 응답과 저장 요청이 같은 모양이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftSnapshot(String title, tools.jackson.databind.JsonNode body, @JsonProperty("legacy_body_md") String legacyBodyMd, List<TextProperty> properties, List<DraftRelation> relations) {
        public static DraftSnapshot from(ContentData.DraftSnapshot v) { return v == null ? null : new DraftSnapshot(v.title(), v.body(), v.legacyBodyMd(), v.properties() == null ? null : v.properties().stream().map(TextProperty::from).toList(), v.relations() == null ? null : v.relations().stream().map(DraftRelation::from).toList()); }
        public ContentData.DraftSnapshot internal() { return new ContentData.DraftSnapshot(title, body, legacyBodyMd, properties == null ? null : properties.stream().map(v -> v == null ? null : v.internal()).toList(), relations == null ? null : relations.stream().map(v -> v == null ? null : v.internal()).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshDraft(UUID id, String kind, @JsonProperty("draft_revision") Long draftRevision, Boolean matched, @JsonProperty("updated_at") OffsetDateTime updatedAt, RefreshDocument document, RefreshCandidate candidate, @JsonProperty("base_version_id") UUID baseVersionId, @JsonProperty("base_revision_no") Long baseRevisionNo, @JsonProperty("base_snapshot") Snapshot baseSnapshot, @JsonProperty("base_mismatch") Boolean baseMismatch, @JsonProperty("left_revision_no") Long leftRevisionNo, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {
        public static RefreshDraft from(ContentData.RefreshDraft v) { return v == null ? null : new RefreshDraft(v.id(), v.kind(), v.draftRevision(), v.matched(), v.updatedAt(), RefreshDocument.from(v.document()), RefreshCandidate.from(v.candidate()), v.baseVersionId(), v.baseRevisionNo(), Snapshot.from(v.baseSnapshot()), v.baseMismatch(), v.leftRevisionNo(), DraftSnapshot.from(v.leftSnapshot()), DraftSnapshot.from(v.rightSnapshot())); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftSave(@JsonProperty("draft_revision") Long draftRevision, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {
        public ContentData.DraftSave internal() { return new ContentData.DraftSave(draftRevision, leftSnapshot == null ? null : leftSnapshot.internal(), rightSnapshot == null ? null : rightSnapshot.internal()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SavedDraft(UUID id, @JsonProperty("draft_revision") Long draftRevision, Boolean matched, @JsonProperty("updated_at") OffsetDateTime updatedAt, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {
        public static SavedDraft from(ContentData.SavedDraft v) { return v == null ? null : new SavedDraft(v.id(), v.draftRevision(), v.matched(), v.updatedAt(), DraftSnapshot.from(v.leftSnapshot()), DraftSnapshot.from(v.rightSnapshot())); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshCreated(@JsonProperty("draft_id") UUID draftId, @JsonProperty("candidate_id") String candidateId, @JsonProperty("file_id") UUID fileId) {
        public static RefreshCreated from(ContentData.RefreshCreated v) { return v == null ? null : new RefreshCreated(v.draftId(), v.candidateId(), v.fileId()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshUpdated(@JsonProperty("draft_id") UUID draftId, @JsonProperty("file_id") UUID fileId, @JsonProperty("revision_no") Long revisionNo) {
        public static RefreshUpdated from(ContentData.RefreshUpdated v) { return v == null ? null : new RefreshUpdated(v.draftId(), v.fileId(), v.revisionNo()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RefreshConfirmed(@JsonProperty("request_id") UUID requestId, String status, List<RefreshCreated> created, List<RefreshUpdated> updated, List<UUID> unchanged) {
        public static RefreshConfirmed from(ContentData.RefreshConfirmed v) { return v == null ? null : new RefreshConfirmed(v.requestId(), v.status(), v.created().stream().map(RefreshCreated::from).toList(), v.updated().stream().map(RefreshUpdated::from).toList(), v.unchanged()); }
    }
    /** REFRESH_DRAFT_CONFLICT 오류 본문의 current. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftConflict(@JsonProperty("draft_revision") Long draftRevision, Boolean matched, @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot, @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {
        public static DraftConflict from(ContentData.DraftConflict v) { return v == null ? null : new DraftConflict(v.draftRevision(), v.matched(), DraftSnapshot.from(v.leftSnapshot()), DraftSnapshot.from(v.rightSnapshot())); }
    }
    /** REFRESH_DOCUMENTS_CHANGED 오류 본문의 documents 한 줄. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChangedDocument(@JsonProperty("draft_id") UUID draftId, @JsonProperty("file_id") UUID fileId) {
        public static ChangedDocument from(ContentData.ChangedDocument v) { return v == null ? null : new ChangedDocument(v.draftId(), v.fileId()); }
    }
}
