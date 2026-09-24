package com.loresentry.gateway.web.content;

import java.net.URI;
import java.util.UUID;
import java.util.Collections;
import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.security.CurrentUser;
import com.loresentry.gateway.web.GatewayFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;

@RestController
public class ContentApiController {
    private final ContentService service;
    public ContentApiController(ContentService service) { this.service = service; }
    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) throw new GatewayFailure(GatewayFailure.Reason.INVALID_REQUEST);
        return values.isEmpty() ? null : values.getFirst();
    }
    @GetMapping("/projects")
    public ResponseEntity<ContentDtos.Projects> listProjects(@CurrentUser UUID userId, HttpServletRequest request) {
        var result = service.listProjects(userId, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Projects.from(result));
    }
    @GetMapping("/projects/trash")
    public ResponseEntity<ContentDtos.Projects> listProjectTrash(@CurrentUser UUID userId, HttpServletRequest request) {
        var result = service.listProjectTrash(userId, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Projects.from(result));
    }
    @PostMapping("/projects")
    public ResponseEntity<ContentDtos.Project> createProject(@CurrentUser UUID userId, @RequestBody ContentDtos.ProjectInput body, HttpServletRequest request) {
        var result = service.createProject(userId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.created(URI.create("/projects/" + result.id())).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ContentDtos.Project> getProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        var result = service.getProject(userId, projectId, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @PatchMapping("/projects/{projectId}")
    public ResponseEntity<ContentDtos.Project> updateProject(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody ContentDtos.ProjectInput body, HttpServletRequest request) {
        var result = service.updateProject(userId, projectId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @PostMapping("/projects/{projectId}/trash")
    public ResponseEntity<Void> trashProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        service.trashProject(userId, projectId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/projects/{projectId}/restore")
    public ResponseEntity<ContentDtos.Project> restoreProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        var result = service.restoreProject(userId, projectId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @DeleteMapping("/projects/{projectId}")
    public ResponseEntity<Void> deleteProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        service.deleteProject(userId, projectId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/projects/{projectId}/files")
    public ResponseEntity<ContentDtos.Tree> tree(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        var result = service.tree(userId, projectId, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Tree.from(result));
    }
    @GetMapping("/projects/{projectId}/files/trash")
    public ResponseEntity<ContentDtos.Trash> fileTrash(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        var result = service.fileTrash(userId, projectId, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Trash.from(result));
    }
    @PostMapping("/projects/{projectId}/files")
    public ResponseEntity<Object> createFile(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody ContentDtos.FileCreate body, HttpServletRequest request) {
        var result = service.createFile(userId, projectId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result.document() != null ? ContentDtos.Document.from(result.document()) : ContentDtos.Episode.from(result.episode()));
    }
    @PatchMapping("/files/{fileId}")
    public ResponseEntity<ContentDtos.Document> renameFile(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Rename body, HttpServletRequest request) {
        var result = service.renameFile(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Document.from(result));
    }
    @PatchMapping("/files/{fileId}/position")
    public ResponseEntity<ContentDtos.Document> moveFile(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Move body, HttpServletRequest request) {
        var result = service.moveFile(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Document.from(result));
    }
    @PostMapping("/files/{fileId}/trash")
    public ResponseEntity<Void> trashFile(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        service.trashFile(userId, fileId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/files/{fileId}/restore")
    public ResponseEntity<ContentDtos.Document> restoreFile(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        var result = service.restoreFile(userId, fileId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Document.from(result));
    }
    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> deleteFile(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        service.deleteFile(userId, fileId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PatchMapping("/episodes/{episodeId}")
    public ResponseEntity<ContentDtos.Episode> renameEpisode(@CurrentUser UUID userId, @PathVariable UUID episodeId, @RequestBody ContentDtos.Rename body, HttpServletRequest request) {
        var result = service.renameEpisode(userId, episodeId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Episode.from(result));
    }
    @DeleteMapping("/episodes/{episodeId}")
    public ResponseEntity<Void> deleteEpisode(@CurrentUser UUID userId, @PathVariable UUID episodeId, HttpServletRequest request) {
        service.deleteEpisode(userId, episodeId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/files/{fileId}/content")
    public ResponseEntity<ContentDtos.Content> getContent(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        var result = service.getContent(userId, fileId, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @PutMapping("/files/{fileId}/content")
    public ResponseEntity<ContentDtos.Content> saveContent(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Snapshot body, HttpServletRequest request) {
        var result = service.saveContent(userId, fileId, body == null ? null : body.internal(), new Conditions(single(request, "If-Match"), single(request, "X-Save-Id"), null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @PutMapping("/files/{fileId}/lock")
    public ResponseEntity<ContentDtos.Content> setLocked(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Lock body, HttpServletRequest request) {
        var result = service.setLocked(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @GetMapping("/files/{fileId}/versions")
    public ResponseEntity<ContentDtos.Versions> versions(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        var result = service.versions(userId, fileId, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Versions.from(result));
    }
    @PostMapping("/files/{fileId}/versions")
    public ResponseEntity<ContentDtos.Version> createVersion(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody(required = false) ContentDtos.NamedVersion body, HttpServletRequest request) {
        var result = service.createVersion(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ContentDtos.Version.from(result));
    }
    @PostMapping("/files/{fileId}/versions/{versionId}/restore")
    public ResponseEntity<ContentDtos.Content> restoreVersion(@CurrentUser UUID userId, @PathVariable UUID fileId, @PathVariable UUID versionId, HttpServletRequest request) {
        var result = service.restoreVersion(userId, fileId, versionId, new Conditions(single(request, "If-Match"), null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @DeleteMapping("/files/{fileId}/versions/{versionId}")
    public ResponseEntity<Void> deleteVersion(@CurrentUser UUID userId, @PathVariable UUID fileId, @PathVariable UUID versionId, HttpServletRequest request) {
        service.deleteVersion(userId, fileId, versionId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/projects/{projectId}/search")
    public ResponseEntity<ContentDtos.Hits> search(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestParam(name = "q", required = false) String query, HttpServletRequest request) {
        var result = service.search(userId, projectId, query, new Conditions(null, null, single(request, "If-None-Match")));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Hits.from(result));
    }
}
