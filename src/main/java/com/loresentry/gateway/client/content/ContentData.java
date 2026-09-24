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
    public record Relation(@JsonProperty("relation_key") String relationKey, @JsonProperty("target_document_id") UUID targetDocumentId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(String title, @JsonProperty("body_md") String bodyMd, List<TextProperty> properties, List<Relation> relations) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Content(UUID id, @JsonProperty("project_id") UUID projectId, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, @JsonProperty("body_md") String bodyMd, List<TextProperty> properties, List<Relation> relations, Boolean locked, @JsonProperty("char_count") Integer charCount, @JsonProperty("revision_no") Long revisionNo, @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
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
}
