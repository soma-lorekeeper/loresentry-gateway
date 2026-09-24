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
    public record Document(UUID id, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, String rank, Boolean locked, @JsonProperty("char_count") Integer charCount, @JsonProperty("revision_no") Long revisionNo, @JsonProperty("trashed_at") OffsetDateTime trashedAt, @JsonProperty("updated_at") OffsetDateTime updatedAt) {
        public static Document from(ContentData.Document value) { return value == null ? null : new Document(value.id(), value.title(), value.folderCode(), value.episodeId(), value.rank(), value.locked(), value.charCount(), value.revisionNo(), value.trashedAt(), value.updatedAt()); }
        public ContentData.Document internal() { return new ContentData.Document(id, title, folderCode, episodeId, rank, locked, charCount, revisionNo, trashedAt, updatedAt); }
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
    public record Relation(@JsonProperty("relation_key") String relationKey, @JsonProperty("target_document_id") UUID targetDocumentId) {
        public static Relation from(ContentData.Relation value) { return value == null ? null : new Relation(value.relationKey(), value.targetDocumentId()); }
        public ContentData.Relation internal() { return new ContentData.Relation(relationKey, targetDocumentId); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(String title, @JsonProperty("body_md") String bodyMd, List<TextProperty> properties, List<Relation> relations) {
        public static Snapshot from(ContentData.Snapshot value) { return value == null ? null : new Snapshot(value.title(), value.bodyMd(), value.properties() == null ? null : value.properties().stream().map(TextProperty::from).toList(), value.relations() == null ? null : value.relations().stream().map(Relation::from).toList()); }
        public ContentData.Snapshot internal() { return new ContentData.Snapshot(title, bodyMd, properties == null ? null : properties.stream().map(TextProperty::internal).toList(), relations == null ? null : relations.stream().map(Relation::internal).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Content(UUID id, @JsonProperty("project_id") UUID projectId, String title, @JsonProperty("folder_code") String folderCode, @JsonProperty("episode_id") UUID episodeId, @JsonProperty("body_md") String bodyMd, List<TextProperty> properties, List<Relation> relations, Boolean locked, @JsonProperty("char_count") Integer charCount, @JsonProperty("revision_no") Long revisionNo, @JsonProperty("updated_at") OffsetDateTime updatedAt) {
        public static Content from(ContentData.Content value) { return value == null ? null : new Content(value.id(), value.projectId(), value.title(), value.folderCode(), value.episodeId(), value.bodyMd(), value.properties() == null ? null : value.properties().stream().map(TextProperty::from).toList(), value.relations() == null ? null : value.relations().stream().map(Relation::from).toList(), value.locked(), value.charCount(), value.revisionNo(), value.updatedAt()); }
        public ContentData.Content internal() { return new ContentData.Content(id, projectId, title, folderCode, episodeId, bodyMd, properties == null ? null : properties.stream().map(TextProperty::internal).toList(), relations == null ? null : relations.stream().map(Relation::internal).toList(), locked, charCount, revisionNo, updatedAt); }
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
}
