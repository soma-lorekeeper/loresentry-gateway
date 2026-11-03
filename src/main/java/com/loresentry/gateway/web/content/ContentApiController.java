package com.loresentry.gateway.web.content;

import java.net.URI;
import com.loresentry.gateway.client.SupportedLocale;
import java.util.UUID;
import java.util.Collections;
import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.security.CurrentUser;
import com.loresentry.gateway.web.GatewayFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;

/**
 * 브라우저의 content API 요청을 content 서비스로 넘기는 컨트롤러.
 *
 * <p>각 핸들러의 첫 줄은 진입 로그다. 어느 핸들러가 어떤 프로젝트·파일을 처리하는지 도메인 ID(경로 변수의 UUID,
 * 문서 저장의 X-Save-Id)를 남긴다. 나머지 입력(본문의 각 필드, 쿼리, If-Match·If-None-Match)은 param으로 남기고,
 * 제목·원고·검색어 q 같은 문자열은 LogField가 가려 길이만, JSON 본문은 바이트 수, 목록은 개수만 남는다. user.id는 SessionFilter가 붙인다.
 */
@RestController
public class ContentApiController {
    private final ContentService service;
    public ContentApiController(ContentService service) { this.service = service; }
    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) throw new GatewayFailure(GatewayFailure.Reason.INVALID_REQUEST);
        return values.isEmpty() ? null : values.getFirst();
    }
    @PostMapping("/feedback")
    public ResponseEntity<ContentDtos.FeedbackCreated> createFeedback(@CurrentUser UUID userId, @RequestBody ContentDtos.FeedbackInput body) {
        // category는 정해진 낱말일 것이나 gateway에 허용 목록이 없어 가려서 남긴다
        AppLogger.current().info("feedback create requested", LogField.param("category", body == null ? null : body.category()), LogField.param("message", body == null ? null : body.message()),
                LogField.param("page", body == null ? null : body.page()), LogField.param("client", body == null ? null : body.client()));
        var result = service.createFeedback(userId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ContentDtos.FeedbackCreated.from(result));
    }
    @GetMapping("/projects")
    public ResponseEntity<ContentDtos.Projects> listProjects(@CurrentUser UUID userId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("project list requested", LogField.param("if_none_match", ifNoneMatch));
        var result = service.listProjects(userId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Projects.from(result));
    }
    @GetMapping("/projects/trash")
    public ResponseEntity<ContentDtos.Projects> listProjectTrash(@CurrentUser UUID userId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("project trash list requested", LogField.param("if_none_match", ifNoneMatch));
        var result = service.listProjectTrash(userId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Projects.from(result));
    }
    @PostMapping("/projects")
    public ResponseEntity<ContentDtos.Project> createProject(@CurrentUser UUID userId, @RequestBody ContentDtos.ProjectInput body, HttpServletRequest request) {
        AppLogger.current().info("project create requested", LogField.param("name", body == null ? null : body.name()), LogField.param("description", body == null ? null : body.description()));
        var result = service.createProject(userId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.created(URI.create("/projects/" + result.id())).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @PostMapping("/projects/sample")
    public ResponseEntity<ContentDtos.Project> createSampleProject(@CurrentUser UUID userId, @RequestParam(name = "locale", required = false) String locale) {
        AppLogger.current().info("sample project create requested", LogField.code("locale", locale, SupportedLocale.VALUES));
        var result = service.createSampleProject(userId, locale, new Conditions(null, null, null));
        return ResponseEntity.created(URI.create("/projects/" + result.id())).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ContentDtos.Project> getProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("project get requested", LogField.projectId(projectId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.getProject(userId, projectId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @PatchMapping("/projects/{projectId}")
    public ResponseEntity<ContentDtos.Project> updateProject(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody ContentDtos.ProjectInput body, HttpServletRequest request) {
        AppLogger.current().info("project update requested", LogField.projectId(projectId), LogField.param("name", body == null ? null : body.name()),
                LogField.param("description", body == null ? null : body.description()));
        var result = service.updateProject(userId, projectId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @PostMapping("/projects/{projectId}/trash")
    public ResponseEntity<Void> trashProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        AppLogger.current().info("project trash requested", LogField.projectId(projectId));
        service.trashProject(userId, projectId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/projects/{projectId}/restore")
    public ResponseEntity<ContentDtos.Project> restoreProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        AppLogger.current().info("project restore requested", LogField.projectId(projectId));
        var result = service.restoreProject(userId, projectId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Project.from(result));
    }
    @DeleteMapping("/projects/{projectId}")
    public ResponseEntity<Void> deleteProject(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        AppLogger.current().info("project delete requested", LogField.projectId(projectId));
        service.deleteProject(userId, projectId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/projects/{projectId}/files")
    public ResponseEntity<ContentDtos.Tree> tree(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("file tree get requested", LogField.projectId(projectId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.tree(userId, projectId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Tree.from(result));
    }
    @GetMapping("/projects/{projectId}/files/trash")
    public ResponseEntity<ContentDtos.Trash> fileTrash(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("file trash list requested", LogField.projectId(projectId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.fileTrash(userId, projectId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Trash.from(result));
    }
    @PostMapping("/projects/{projectId}/files")
    public ResponseEntity<Object> createFile(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody ContentDtos.FileCreate body, HttpServletRequest request) {
        // 새 파일이 들어갈 회차는 프로젝트처럼 새 파일의 범위라 전용 필드(episode_id)다. kind·folder_code는 허용 목록이 없어 가린다
        AppLogger.current().info("file create requested", LogField.projectId(projectId), LogField.episodeId(body == null ? null : body.episodeId()),
                LogField.param("kind", body == null ? null : body.kind()), LogField.param("title", body == null ? null : body.title()), LogField.param("folder_code", body == null ? null : body.folderCode()));
        var result = service.createFile(userId, projectId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result.document() != null ? ContentDtos.Document.from(result.document()) : ContentDtos.Episode.from(result.episode()));
    }
    @PatchMapping("/files/{fileId}")
    public ResponseEntity<ContentDtos.Document> renameFile(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Rename body, HttpServletRequest request) {
        AppLogger.current().info("file rename requested", LogField.fileId(fileId), LogField.param("title", body == null ? null : body.title()));
        var result = service.renameFile(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Document.from(result));
    }
    @PatchMapping("/files/{fileId}/position")
    public ResponseEntity<ContentDtos.Document> moveFile(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Move body, HttpServletRequest request) {
        // 옮길 위치(폴더·회차)와 기준 파일은 대상이 아니라 참조라 역할 이름을 붙인 param이다
        AppLogger.current().info("file move requested", LogField.fileId(fileId), LogField.param("target_folder_code", body == null ? null : body.folderCode()),
                LogField.param("target_episode_id", body == null ? null : body.episodeId()), LogField.param("before_file_id", body == null ? null : body.beforeFileId()));
        var result = service.moveFile(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Document.from(result));
    }
    @PostMapping("/files/{fileId}/trash")
    public ResponseEntity<Void> trashFile(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        AppLogger.current().info("file trash requested", LogField.fileId(fileId));
        service.trashFile(userId, fileId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/files/{fileId}/restore")
    public ResponseEntity<ContentDtos.Document> restoreFile(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        AppLogger.current().info("file restore requested", LogField.fileId(fileId));
        var result = service.restoreFile(userId, fileId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Document.from(result));
    }
    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> deleteFile(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        AppLogger.current().info("file delete requested", LogField.fileId(fileId));
        service.deleteFile(userId, fileId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PatchMapping("/episodes/{episodeId}")
    public ResponseEntity<ContentDtos.Episode> renameEpisode(@CurrentUser UUID userId, @PathVariable UUID episodeId, @RequestBody ContentDtos.Rename body, HttpServletRequest request) {
        AppLogger.current().info("episode rename requested", LogField.episodeId(episodeId), LogField.param("title", body == null ? null : body.title()));
        var result = service.renameEpisode(userId, episodeId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Episode.from(result));
    }
    @DeleteMapping("/episodes/{episodeId}")
    public ResponseEntity<Void> deleteEpisode(@CurrentUser UUID userId, @PathVariable UUID episodeId, HttpServletRequest request) {
        AppLogger.current().info("episode delete requested", LogField.episodeId(episodeId));
        service.deleteEpisode(userId, episodeId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/files/{fileId}/content")
    public ResponseEntity<ContentDtos.Content> getContent(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("file content get requested", LogField.fileId(fileId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.getContent(userId, fileId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @PutMapping("/files/{fileId}/content")
    public ResponseEntity<ContentDtos.Content> saveContent(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Snapshot body, HttpServletRequest request) {
        String saveId = single(request, "X-Save-Id");
        String ifMatch = single(request, "If-Match");
        AppLogger.current().info("file content save requested", LogField.fileId(fileId), LogField.saveId(saveId), LogField.param("if_match", ifMatch),
                LogField.param("title", body == null ? null : body.title()), LogField.param("body", body == null ? null : body.body()), LogField.param("legacy_body_md", body == null ? null : body.legacyBodyMd()),
                LogField.param("properties", body == null ? null : body.properties()), LogField.param("relations", body == null ? null : body.relations()));
        var result = service.saveContent(userId, fileId, body == null ? null : body.internal(), new Conditions(ifMatch, saveId, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @PutMapping("/files/{fileId}/image")
    public ResponseEntity<ContentDtos.Document> setDocumentImage(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.DocumentImage body, HttpServletRequest request) {
        // 대상은 파일이고 이미지는 연결할 상대라 param이다
        AppLogger.current().info("file image set requested", LogField.fileId(fileId), LogField.param("attached_image_id", body == null ? null : body.imageId()));
        var result = service.setDocumentImage(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Document.from(result));
    }
    @DeleteMapping("/files/{fileId}/image")
    public ResponseEntity<Void> clearDocumentImage(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        AppLogger.current().info("file image clear requested", LogField.fileId(fileId));
        service.clearDocumentImage(userId, fileId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PutMapping("/files/{fileId}/lock")
    public ResponseEntity<ContentDtos.Content> setLocked(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody ContentDtos.Lock body, HttpServletRequest request) {
        AppLogger.current().info("file lock set requested", LogField.fileId(fileId), LogField.param("locked", body == null ? null : body.locked()));
        var result = service.setLocked(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @GetMapping("/files/{fileId}/versions")
    public ResponseEntity<ContentDtos.Versions> versions(@CurrentUser UUID userId, @PathVariable UUID fileId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("file version list requested", LogField.fileId(fileId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.versions(userId, fileId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Versions.from(result));
    }
    @PostMapping("/files/{fileId}/versions")
    public ResponseEntity<ContentDtos.Version> createVersion(@CurrentUser UUID userId, @PathVariable UUID fileId, @RequestBody(required = false) ContentDtos.NamedVersion body, HttpServletRequest request) {
        AppLogger.current().info("file version create requested", LogField.fileId(fileId), LogField.param("label", body == null ? null : body.label()));
        var result = service.createVersion(userId, fileId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ContentDtos.Version.from(result));
    }
    @PostMapping("/files/{fileId}/versions/{versionId}/restore")
    public ResponseEntity<ContentDtos.Content> restoreVersion(@CurrentUser UUID userId, @PathVariable UUID fileId, @PathVariable UUID versionId, HttpServletRequest request) {
        String ifMatch = single(request, "If-Match");
        AppLogger.current().info("file version restore requested", LogField.fileId(fileId), LogField.versionId(versionId), LogField.param("if_match", ifMatch));
        var result = service.restoreVersion(userId, fileId, versionId, new Conditions(ifMatch, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Content.from(result));
    }
    @DeleteMapping("/files/{fileId}/versions/{versionId}")
    public ResponseEntity<Void> deleteVersion(@CurrentUser UUID userId, @PathVariable UUID fileId, @PathVariable UUID versionId, HttpServletRequest request) {
        AppLogger.current().info("file version delete requested", LogField.fileId(fileId), LogField.versionId(versionId));
        service.deleteVersion(userId, fileId, versionId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/projects/{projectId}/search")
    public ResponseEntity<ContentDtos.Hits> search(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestParam(name = "q", required = false) String query, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("project search requested", LogField.projectId(projectId), LogField.param("query", query), LogField.param("if_none_match", ifNoneMatch));
        var result = service.search(userId, projectId, query, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Hits.from(result));
    }
    @GetMapping("/projects/{projectId}/memos")
    public ResponseEntity<ContentDtos.Memos> listMemos(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestParam(name = "scope", required = false) String scope, @RequestParam(name = "document_id", required = false) UUID documentId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        // scope는 정해진 낱말일 것이나 gateway에 허용 목록이 없어 가려서 남긴다
        AppLogger.current().info("memo list requested", LogField.projectId(projectId), LogField.documentId(documentId), LogField.param("scope", scope),
                LogField.param("if_none_match", ifNoneMatch));
        var result = service.listMemos(userId, projectId, scope, documentId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Memos.from(result));
    }
    @PostMapping("/projects/{projectId}/memos")
    public ResponseEntity<ContentDtos.Memo> createMemo(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody ContentDtos.MemoCreate body, HttpServletRequest request) {
        // 메모가 붙을 문서는 메모의 범위라 전용 필드(document_id)다
        AppLogger.current().info("memo create requested", LogField.projectId(projectId), LogField.documentId(body == null ? null : body.documentId()),
                LogField.param("scope", body == null ? null : body.scope()), LogField.param("title", body == null ? null : body.title()), LogField.param("body", body == null ? null : body.body()));
        var result = service.createMemo(userId, projectId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.created(URI.create("/memos/" + result.id())).cacheControl(CacheControl.noStore()).body(ContentDtos.Memo.from(result));
    }
    @PatchMapping("/memos/{memoId}")
    public ResponseEntity<ContentDtos.Memo> updateMemo(@CurrentUser UUID userId, @PathVariable UUID memoId, @RequestBody ContentDtos.MemoUpdate body, HttpServletRequest request) {
        AppLogger.current().info("memo update requested", LogField.memoId(memoId), LogField.param("title", body == null ? null : body.title()), LogField.param("body", body == null ? null : body.body()));
        var result = service.updateMemo(userId, memoId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Memo.from(result));
    }
    @DeleteMapping("/memos/{memoId}")
    public ResponseEntity<Void> deleteMemo(@CurrentUser UUID userId, @PathVariable UUID memoId, HttpServletRequest request) {
        AppLogger.current().info("memo delete requested", LogField.memoId(memoId));
        service.deleteMemo(userId, memoId, new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/projects/{projectId}/favorites")
    public ResponseEntity<ContentDtos.Favorites> listFavorites(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("favorite list requested", LogField.projectId(projectId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.listFavorites(userId, projectId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Favorites.from(result));
    }
    @PutMapping("/projects/{projectId}/favorites/{fileId}")
    public ResponseEntity<ContentDtos.Favorites> addFavorite(@CurrentUser UUID userId, @PathVariable UUID projectId, @PathVariable UUID fileId, HttpServletRequest request) {
        AppLogger.current().info("favorite add requested", LogField.projectId(projectId), LogField.fileId(fileId));
        var result = service.addFavorite(userId, projectId, fileId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Favorites.from(result));
    }
    @DeleteMapping("/projects/{projectId}/favorites/{fileId}")
    public ResponseEntity<ContentDtos.Favorites> removeFavorite(@CurrentUser UUID userId, @PathVariable UUID projectId, @PathVariable UUID fileId, HttpServletRequest request) {
        AppLogger.current().info("favorite remove requested", LogField.projectId(projectId), LogField.fileId(fileId));
        var result = service.removeFavorite(userId, projectId, fileId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Favorites.from(result));
    }
    @GetMapping("/projects/{projectId}/graph")
    public ResponseEntity<ContentDtos.Graph> graph(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("graph get requested", LogField.projectId(projectId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.graph(userId, projectId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Graph.from(result));
    }
    @GetMapping("/projects/{projectId}/workspace-state")
    public ResponseEntity<ContentDtos.WorkspaceState> loadWorkspaceState(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("workspace state get requested", LogField.projectId(projectId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.loadWorkspaceState(userId, projectId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.WorkspaceState.from(result));
    }
    @PutMapping("/projects/{projectId}/workspace-state")
    public ResponseEntity<Void> saveWorkspaceState(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody ContentDtos.WorkspaceState body, HttpServletRequest request) {
        AppLogger.current().info("workspace state save requested", LogField.projectId(projectId), LogField.param("layout", body == null ? null : body.layout()));
        service.saveWorkspaceState(userId, projectId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/projects/{projectId}/images")
    public ResponseEntity<ContentDtos.ImageTicket> createImageTicket(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody ContentDtos.ImageTicketRequest body, HttpServletRequest request) {
        // content_type은 MIME 문자열이고 gateway에 허용 목록이 없어 가린다
        AppLogger.current().info("image upload ticket create requested", LogField.projectId(projectId), LogField.param("file_name", body == null ? null : body.fileName()),
                LogField.param("content_type", body == null ? null : body.contentType()), LogField.param("size_bytes", body == null ? null : body.sizeBytes()));
        var result = service.createImageTicket(userId, projectId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.created(URI.create("/projects/" + projectId + "/images/" + result.imageId())).cacheControl(CacheControl.noStore()).body(ContentDtos.ImageTicket.from(result));
    }
    @PostMapping("/projects/{projectId}/images/{imageId}/complete")
    public ResponseEntity<ContentDtos.Image> completeImage(@CurrentUser UUID userId, @PathVariable UUID projectId, @PathVariable UUID imageId, HttpServletRequest request) {
        AppLogger.current().info("image upload complete requested", LogField.projectId(projectId), LogField.imageId(imageId));
        var result = service.completeImage(userId, projectId, imageId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Image.from(result));
    }
    @GetMapping("/projects/{projectId}/images/{imageId}")
    public ResponseEntity<ContentDtos.Image> getImage(@CurrentUser UUID userId, @PathVariable UUID projectId, @PathVariable UUID imageId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("image get requested", LogField.projectId(projectId), LogField.imageId(imageId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.getImage(userId, projectId, imageId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.Image.from(result));
    }
    @PostMapping("/projects/{projectId}/graph-refresh")
    public ResponseEntity<ContentDtos.RefreshStart> startRefresh(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        AppLogger.current().info("graph refresh start requested", LogField.projectId(projectId));
        var result = service.startRefresh(userId, projectId, new Conditions(null, null, null));
        // Content 와 같게 문서를 보냈으면(GENERATING) 202, 바뀐 문서가 없으면(NO_CHANGES) 200 이다.
        int status = "GENERATING".equals(result.status()) ? 202 : 200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ContentDtos.RefreshStart.from(result));
    }
    @GetMapping("/projects/{projectId}/graph-refresh")
    public ResponseEntity<ContentDtos.RefreshSummary> latestRefresh(@CurrentUser UUID userId, @PathVariable UUID projectId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("graph refresh latest get requested", LogField.projectId(projectId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.latestRefresh(userId, projectId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.RefreshSummary.from(result));
    }
    @GetMapping("/projects/{projectId}/graph-refresh/{requestId}")
    public ResponseEntity<ContentDtos.RefreshDetail> getRefresh(@CurrentUser UUID userId, @PathVariable UUID projectId, @PathVariable UUID requestId, HttpServletRequest request) {
        String ifNoneMatch = single(request, "If-None-Match");
        AppLogger.current().info("graph refresh get requested", LogField.projectId(projectId), LogField.refreshRequestId(requestId), LogField.param("if_none_match", ifNoneMatch));
        var result = service.getRefresh(userId, projectId, requestId, new Conditions(null, null, ifNoneMatch));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.RefreshDetail.from(result));
    }
    @PatchMapping("/projects/{projectId}/graph-refresh/{requestId}/drafts/{draftId}")
    public ResponseEntity<ContentDtos.SavedDraft> saveRefreshDraft(@CurrentUser UUID userId, @PathVariable UUID projectId, @PathVariable UUID requestId, @PathVariable UUID draftId, @RequestBody ContentDtos.DraftSave body, HttpServletRequest request) {
        // 양쪽 스냅샷은 본문 바이트 수와 관계 개수만 남긴다(스냅샷 객체는 LogField가 받지 않는다)
        var left = body == null ? null : body.leftSnapshot();
        var right = body == null ? null : body.rightSnapshot();
        AppLogger.current().info("graph refresh draft save requested", LogField.projectId(projectId), LogField.refreshRequestId(requestId),
                LogField.refreshDraftId(draftId), LogField.param("draft_revision", body == null ? null : body.draftRevision()),
                LogField.param("left_body", left == null ? null : left.body()), LogField.param("left_relations", left == null ? null : left.relations()),
                LogField.param("right_body", right == null ? null : right.body()), LogField.param("right_relations", right == null ? null : right.relations()));
        var result = service.saveRefreshDraft(userId, projectId, requestId, draftId, body == null ? null : body.internal(), new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.SavedDraft.from(result));
    }
    @PostMapping("/projects/{projectId}/graph-refresh/{requestId}/confirm")
    public ResponseEntity<ContentDtos.RefreshConfirmed> confirmRefresh(@CurrentUser UUID userId, @PathVariable UUID projectId, @PathVariable UUID requestId, HttpServletRequest request) {
        AppLogger.current().info("graph refresh confirm requested", LogField.projectId(projectId), LogField.refreshRequestId(requestId));
        var result = service.confirmRefresh(userId, projectId, requestId, new Conditions(null, null, null));
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(ContentDtos.RefreshConfirmed.from(result));
    }
}
