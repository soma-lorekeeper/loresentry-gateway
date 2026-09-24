package com.loresentry.gateway.client.content;

import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import com.loresentry.gateway.client.UpstreamException;

@Component
public class ContentApiClient {
    private final RestClient client;
    public ContentApiClient(RestClient contentApiRestClient) { this.client = contentApiRestClient; }
    public record Conditions(String ifMatch, String saveId, String ifNoneMatch) {}

    private <T> T call(String method, String path, Map<String, ?> variables, UUID userId,
                       Object body, Class<T> type, int expected, Conditions conditions) {
        try {
            var request = client.method(HttpMethod.valueOf(method)).uri(path, variables)
                    .header("X-User-Id", Objects.requireNonNull(userId).toString());
            if (conditions != null) {
                if (conditions.ifMatch() != null) request.header("If-Match", conditions.ifMatch());
                if (conditions.saveId() != null) request.header("X-Save-Id", conditions.saveId());
                if (conditions.ifNoneMatch() != null) request.header("If-None-Match", conditions.ifNoneMatch());
            }
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            var response = request.retrieve().toEntity(type);
            if (response.getStatusCode().value() != expected || (type != Void.class && response.getBody() == null)) {
                throw new UpstreamException("content", "Invalid Content response", null);
            }
            return response.getBody();
        } catch (RestClientException failure) {
            throw new UpstreamException("content", "Content request failed", failure);
        }
    }
    public ContentData.Projects listProjects(UUID userId, Conditions conditions) {
        return call("GET", "/projects", Map.of(), userId, null, ContentData.Projects.class, 200, conditions);
    }
    public ContentData.Projects listProjectTrash(UUID userId, Conditions conditions) {
        return call("GET", "/projects/trash", Map.of(), userId, null, ContentData.Projects.class, 200, conditions);
    }
    public ContentData.Project createProject(UUID userId, ContentData.ProjectInput body, Conditions conditions) {
        return call("POST", "/projects", Map.of(), userId, body, ContentData.Project.class, 201, conditions);
    }
    public ContentData.Project getProject(UUID userId, UUID projectId, Conditions conditions) {
        return call("GET", "/projects/{projectId}", Map.of("projectId", projectId), userId, null, ContentData.Project.class, 200, conditions);
    }
    public ContentData.Project updateProject(UUID userId, UUID projectId, ContentData.ProjectInput body, Conditions conditions) {
        return call("PATCH", "/projects/{projectId}", Map.of("projectId", projectId), userId, body, ContentData.Project.class, 200, conditions);
    }
    public Void trashProject(UUID userId, UUID projectId, Conditions conditions) {
        return call("POST", "/projects/{projectId}/trash", Map.of("projectId", projectId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Project restoreProject(UUID userId, UUID projectId, Conditions conditions) {
        return call("POST", "/projects/{projectId}/restore", Map.of("projectId", projectId), userId, null, ContentData.Project.class, 200, conditions);
    }
    public Void deleteProject(UUID userId, UUID projectId, Conditions conditions) {
        return call("DELETE", "/projects/{projectId}", Map.of("projectId", projectId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Tree tree(UUID userId, UUID projectId, Conditions conditions) {
        return call("GET", "/projects/{projectId}/files", Map.of("projectId", projectId), userId, null, ContentData.Tree.class, 200, conditions);
    }
    public ContentData.Trash fileTrash(UUID userId, UUID projectId, Conditions conditions) {
        return call("GET", "/projects/{projectId}/files/trash", Map.of("projectId", projectId), userId, null, ContentData.Trash.class, 200, conditions);
    }
    public FileCreated createFile(UUID userId, UUID projectId, ContentData.FileCreate body, Conditions conditions) {
        if (body != null && "episode".equals(body.kind())) return new FileCreated(null, call("POST", "/projects/{projectId}/files", Map.of("projectId", projectId), userId, body, ContentData.Episode.class, 201, conditions));
        return new FileCreated(call("POST", "/projects/{projectId}/files", Map.of("projectId", projectId), userId, body, ContentData.Document.class, 201, conditions), null);
    }
    public ContentData.Document renameFile(UUID userId, UUID fileId, ContentData.Rename body, Conditions conditions) {
        return call("PATCH", "/files/{fileId}", Map.of("fileId", fileId), userId, body, ContentData.Document.class, 200, conditions);
    }
    public ContentData.Document moveFile(UUID userId, UUID fileId, ContentData.Move body, Conditions conditions) {
        return call("PATCH", "/files/{fileId}/position", Map.of("fileId", fileId), userId, body, ContentData.Document.class, 200, conditions);
    }
    public Void trashFile(UUID userId, UUID fileId, Conditions conditions) {
        return call("POST", "/files/{fileId}/trash", Map.of("fileId", fileId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Document restoreFile(UUID userId, UUID fileId, Conditions conditions) {
        return call("POST", "/files/{fileId}/restore", Map.of("fileId", fileId), userId, null, ContentData.Document.class, 200, conditions);
    }
    public Void deleteFile(UUID userId, UUID fileId, Conditions conditions) {
        return call("DELETE", "/files/{fileId}", Map.of("fileId", fileId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Episode renameEpisode(UUID userId, UUID episodeId, ContentData.Rename body, Conditions conditions) {
        return call("PATCH", "/episodes/{episodeId}", Map.of("episodeId", episodeId), userId, body, ContentData.Episode.class, 200, conditions);
    }
    public Void deleteEpisode(UUID userId, UUID episodeId, Conditions conditions) {
        return call("DELETE", "/episodes/{episodeId}", Map.of("episodeId", episodeId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Content getContent(UUID userId, UUID fileId, Conditions conditions) {
        return call("GET", "/files/{fileId}/content", Map.of("fileId", fileId), userId, null, ContentData.Content.class, 200, conditions);
    }
    public ContentData.Content saveContent(UUID userId, UUID fileId, ContentData.Snapshot body, Conditions conditions) {
        return call("PUT", "/files/{fileId}/content", Map.of("fileId", fileId), userId, body, ContentData.Content.class, 200, conditions);
    }
    public ContentData.Content setLocked(UUID userId, UUID fileId, ContentData.Lock body, Conditions conditions) {
        return call("PUT", "/files/{fileId}/lock", Map.of("fileId", fileId), userId, body, ContentData.Content.class, 200, conditions);
    }
    public ContentData.Versions versions(UUID userId, UUID fileId, Conditions conditions) {
        return call("GET", "/files/{fileId}/versions", Map.of("fileId", fileId), userId, null, ContentData.Versions.class, 200, conditions);
    }
    public ContentData.Version createVersion(UUID userId, UUID fileId, ContentData.NamedVersion body, Conditions conditions) {
        return call("POST", "/files/{fileId}/versions", Map.of("fileId", fileId), userId, body, ContentData.Version.class, 201, conditions);
    }
    public ContentData.Content restoreVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) {
        return call("POST", "/files/{fileId}/versions/{versionId}/restore", Map.of("fileId", fileId, "versionId", versionId), userId, null, ContentData.Content.class, 200, conditions);
    }
    public Void deleteVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) {
        return call("DELETE", "/files/{fileId}/versions/{versionId}", Map.of("fileId", fileId, "versionId", versionId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Hits search(UUID userId, UUID projectId, String query, Conditions conditions) {
        return call("GET", "/projects/{projectId}/search?q={query}", Map.of("projectId", projectId, "query", query == null ? "" : query), userId, null, ContentData.Hits.class, 200, conditions);
    }
}
