package com.loresentry.gateway.application;

import java.util.UUID;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.client.content.ContentData;
import com.loresentry.gateway.client.content.FileCreated;
import org.springframework.stereotype.Service;

@Service
public class ContentService {
    private final ContentApiClient client;
    public ContentService(ContentApiClient client) { this.client = client; }
    public ContentData.Projects listProjects(UUID userId, Conditions conditions) { return client.listProjects(userId, conditions); }
    public ContentData.Projects listProjectTrash(UUID userId, Conditions conditions) { return client.listProjectTrash(userId, conditions); }
    public ContentData.Project createProject(UUID userId, ContentData.ProjectInput body, Conditions conditions) { return client.createProject(userId, body, conditions); }
    public ContentData.Project getProject(UUID userId, UUID projectId, Conditions conditions) { return client.getProject(userId, projectId, conditions); }
    public ContentData.Project updateProject(UUID userId, UUID projectId, ContentData.ProjectInput body, Conditions conditions) { return client.updateProject(userId, projectId, body, conditions); }
    public Void trashProject(UUID userId, UUID projectId, Conditions conditions) { return client.trashProject(userId, projectId, conditions); }
    public ContentData.Project restoreProject(UUID userId, UUID projectId, Conditions conditions) { return client.restoreProject(userId, projectId, conditions); }
    public Void deleteProject(UUID userId, UUID projectId, Conditions conditions) { return client.deleteProject(userId, projectId, conditions); }
    public ContentData.Tree tree(UUID userId, UUID projectId, Conditions conditions) { return client.tree(userId, projectId, conditions); }
    public ContentData.Trash fileTrash(UUID userId, UUID projectId, Conditions conditions) { return client.fileTrash(userId, projectId, conditions); }
    public FileCreated createFile(UUID userId, UUID projectId, ContentData.FileCreate body, Conditions conditions) { return client.createFile(userId, projectId, body, conditions); }
    public ContentData.Document renameFile(UUID userId, UUID fileId, ContentData.Rename body, Conditions conditions) { return client.renameFile(userId, fileId, body, conditions); }
    public ContentData.Document moveFile(UUID userId, UUID fileId, ContentData.Move body, Conditions conditions) { return client.moveFile(userId, fileId, body, conditions); }
    public Void trashFile(UUID userId, UUID fileId, Conditions conditions) { return client.trashFile(userId, fileId, conditions); }
    public ContentData.Document restoreFile(UUID userId, UUID fileId, Conditions conditions) { return client.restoreFile(userId, fileId, conditions); }
    public Void deleteFile(UUID userId, UUID fileId, Conditions conditions) { return client.deleteFile(userId, fileId, conditions); }
    public ContentData.Episode renameEpisode(UUID userId, UUID episodeId, ContentData.Rename body, Conditions conditions) { return client.renameEpisode(userId, episodeId, body, conditions); }
    public Void deleteEpisode(UUID userId, UUID episodeId, Conditions conditions) { return client.deleteEpisode(userId, episodeId, conditions); }
    public ContentData.Content getContent(UUID userId, UUID fileId, Conditions conditions) { return client.getContent(userId, fileId, conditions); }
    public ContentData.Content saveContent(UUID userId, UUID fileId, ContentData.Snapshot body, Conditions conditions) { return client.saveContent(userId, fileId, body, conditions); }
    public ContentData.Content setLocked(UUID userId, UUID fileId, ContentData.Lock body, Conditions conditions) { return client.setLocked(userId, fileId, body, conditions); }
    public ContentData.Versions versions(UUID userId, UUID fileId, Conditions conditions) { return client.versions(userId, fileId, conditions); }
    public ContentData.Version createVersion(UUID userId, UUID fileId, ContentData.NamedVersion body, Conditions conditions) { return client.createVersion(userId, fileId, body, conditions); }
    public ContentData.Content restoreVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) { return client.restoreVersion(userId, fileId, versionId, conditions); }
    public Void deleteVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) { return client.deleteVersion(userId, fileId, versionId, conditions); }
    public ContentData.Hits search(UUID userId, UUID projectId, String query, Conditions conditions) { return client.search(userId, projectId, query, conditions); }
}
