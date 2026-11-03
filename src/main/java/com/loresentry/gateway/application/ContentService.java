package com.loresentry.gateway.application;

import java.util.Arrays;
import com.loresentry.gateway.client.SupportedLocale;
import java.util.UUID;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.client.content.ContentData;
import com.loresentry.gateway.client.content.FileCreated;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

@Service
public class ContentService {
    private final ContentApiClient client;
    private final ChatApiClient chat;
    public ContentService(ContentApiClient client, ChatApiClient chat) { this.client = client; this.chat = chat; }
    public ContentData.FeedbackCreated createFeedback(UUID userId, ContentData.FeedbackInput body, Conditions conditions) {
        // category는 정해진 낱말일 것이나 gateway에 허용 목록이 없어 가려서 남긴다
        AppLogger.current().info("feedback create", withConditions(conditions, LogField.param("category", body == null ? null : body.category()),
                LogField.param("message", body == null ? null : body.message()), LogField.param("page", body == null ? null : body.page()),
                LogField.param("client", body == null ? null : body.client())));
        return client.createFeedback(userId, body, conditions);
    }
    public ContentData.Projects listProjects(UUID userId, Conditions conditions) { AppLogger.current().info("project list", withConditions(conditions)); return client.listProjects(userId, conditions); }
    public ContentData.Projects listProjectTrash(UUID userId, Conditions conditions) { AppLogger.current().info("project trash list", withConditions(conditions)); return client.listProjectTrash(userId, conditions); }
    public ContentData.Project createProject(UUID userId, ContentData.ProjectInput body, Conditions conditions) {
        AppLogger.current().info("project create", withConditions(conditions, LogField.param("name", body == null ? null : body.name()),
                LogField.param("description", body == null ? null : body.description())));
        return client.createProject(userId, body, conditions);
    }
    public ContentData.Project createSampleProject(UUID userId, String locale, Conditions conditions) {
        AppLogger.current().info("sample project create", withConditions(conditions, LogField.code("locale", locale, SupportedLocale.VALUES)));
        return client.createSampleProject(userId, locale, conditions);
    }
    public ContentData.Project getProject(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("project get", withConditions(conditions, LogField.projectId(projectId))); return client.getProject(userId, projectId, conditions); }
    public ContentData.Project updateProject(UUID userId, UUID projectId, ContentData.ProjectInput body, Conditions conditions) {
        AppLogger.current().info("project update", withConditions(conditions, LogField.projectId(projectId), LogField.param("name", body == null ? null : body.name()),
                LogField.param("description", body == null ? null : body.description())));
        return client.updateProject(userId, projectId, body, conditions);
    }
    public Void trashProject(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("project trash", withConditions(conditions, LogField.projectId(projectId))); return client.trashProject(userId, projectId, conditions); }
    public ContentData.Project restoreProject(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("project restore", withConditions(conditions, LogField.projectId(projectId))); return client.restoreProject(userId, projectId, conditions); }
    public Void deleteProject(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("project delete", withConditions(conditions, LogField.projectId(projectId)));
        client.deleteProject(userId, projectId, conditions);
        // ai-chat 데이터까지 지웠는지. 결과 줄에 남겨 정리가 빠진 프로젝트를 찾는다
        boolean chatDataDeleted = true;
        try { chat.deleteProjectData(userId, projectId); }
        catch (RuntimeException failure) {
            chatDataDeleted = false;
            AppLogger.current().warn("ai-chat data of deleted project was not removed", LogField.projectId(projectId),
                    LogField.errorType(failure instanceof ChatCallFailure chatFailure ? chatFailure.code() : failure.getClass().getSimpleName()));
        }
        AppLogger.current().info("project delete completed", LogField.projectId(projectId), LogField.result("chat_data_deleted", chatDataDeleted));
        return null;
    }
    public ContentData.Tree tree(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("file tree get", withConditions(conditions, LogField.projectId(projectId))); return client.tree(userId, projectId, conditions); }
    public ContentData.Trash fileTrash(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("file trash list", withConditions(conditions, LogField.projectId(projectId))); return client.fileTrash(userId, projectId, conditions); }
    public FileCreated createFile(UUID userId, UUID projectId, ContentData.FileCreate body, Conditions conditions) {
        // 새 파일이 들어갈 회차는 프로젝트처럼 새 파일의 범위라 전용 필드(episode_id)다. kind·folder_code는 허용 목록이 없어 가린다
        AppLogger.current().info("file create", withConditions(conditions, LogField.projectId(projectId), LogField.episodeId(body == null ? null : body.episodeId()),
                LogField.param("kind", body == null ? null : body.kind()), LogField.param("title", body == null ? null : body.title()),
                LogField.param("folder_code", body == null ? null : body.folderCode())));
        return client.createFile(userId, projectId, body, conditions);
    }
    public ContentData.Document renameFile(UUID userId, UUID fileId, ContentData.Rename body, Conditions conditions) {
        AppLogger.current().info("file rename", withConditions(conditions, LogField.fileId(fileId), LogField.param("title", body == null ? null : body.title())));
        return client.renameFile(userId, fileId, body, conditions);
    }
    public ContentData.Document moveFile(UUID userId, UUID fileId, ContentData.Move body, Conditions conditions) {
        // 옮길 위치(폴더·회차)와 기준 파일은 대상이 아니라 참조라 역할 이름을 붙인 param이다
        AppLogger.current().info("file move", withConditions(conditions, LogField.fileId(fileId),
                LogField.param("target_folder_code", body == null ? null : body.folderCode()), LogField.param("target_episode_id", body == null ? null : body.episodeId()),
                LogField.param("before_file_id", body == null ? null : body.beforeFileId())));
        return client.moveFile(userId, fileId, body, conditions);
    }
    public Void trashFile(UUID userId, UUID fileId, Conditions conditions) { AppLogger.current().info("file trash", withConditions(conditions, LogField.fileId(fileId))); return client.trashFile(userId, fileId, conditions); }
    public ContentData.Document restoreFile(UUID userId, UUID fileId, Conditions conditions) { AppLogger.current().info("file restore", withConditions(conditions, LogField.fileId(fileId))); return client.restoreFile(userId, fileId, conditions); }
    public Void deleteFile(UUID userId, UUID fileId, Conditions conditions) { AppLogger.current().info("file delete", withConditions(conditions, LogField.fileId(fileId))); return client.deleteFile(userId, fileId, conditions); }
    public ContentData.Episode renameEpisode(UUID userId, UUID episodeId, ContentData.Rename body, Conditions conditions) {
        AppLogger.current().info("episode rename", withConditions(conditions, LogField.episodeId(episodeId), LogField.param("title", body == null ? null : body.title())));
        return client.renameEpisode(userId, episodeId, body, conditions);
    }
    public Void deleteEpisode(UUID userId, UUID episodeId, Conditions conditions) { AppLogger.current().info("episode delete", withConditions(conditions, LogField.episodeId(episodeId))); return client.deleteEpisode(userId, episodeId, conditions); }
    public ContentData.Content getContent(UUID userId, UUID fileId, Conditions conditions) { AppLogger.current().info("file content get", withConditions(conditions, LogField.fileId(fileId))); return client.getContent(userId, fileId, conditions); }
    public ContentData.Content saveContent(UUID userId, UUID fileId, ContentData.Snapshot body, Conditions conditions) {
        AppLogger.current().info("file content save", withConditions(conditions, LogField.fileId(fileId), LogField.param("title", body == null ? null : body.title()),
                LogField.param("body", body == null ? null : body.body()), LogField.param("legacy_body_md", body == null ? null : body.legacyBodyMd()),
                LogField.param("properties", body == null ? null : body.properties()), LogField.param("relations", body == null ? null : body.relations())));
        return client.saveContent(userId, fileId, body, conditions);
    }
    public ContentData.Document setDocumentImage(UUID userId, UUID fileId, ContentData.DocumentImage body, Conditions conditions) {
        // 대상은 파일이고 이미지는 연결할 상대라 param이다
        AppLogger.current().info("file image set", withConditions(conditions, LogField.fileId(fileId), LogField.param("attached_image_id", body == null ? null : body.imageId())));
        return client.setDocumentImage(userId, fileId, body, conditions);
    }
    public Void clearDocumentImage(UUID userId, UUID fileId, Conditions conditions) { AppLogger.current().info("file image clear", withConditions(conditions, LogField.fileId(fileId))); return client.clearDocumentImage(userId, fileId, conditions); }
    public ContentData.Content setLocked(UUID userId, UUID fileId, ContentData.Lock body, Conditions conditions) {
        AppLogger.current().info("file lock set", withConditions(conditions, LogField.fileId(fileId), LogField.param("locked", body == null ? null : body.locked())));
        return client.setLocked(userId, fileId, body, conditions);
    }
    public ContentData.Versions versions(UUID userId, UUID fileId, Conditions conditions) { AppLogger.current().info("file version list", withConditions(conditions, LogField.fileId(fileId))); return client.versions(userId, fileId, conditions); }
    public ContentData.Version createVersion(UUID userId, UUID fileId, ContentData.NamedVersion body, Conditions conditions) {
        AppLogger.current().info("file version create", withConditions(conditions, LogField.fileId(fileId), LogField.param("label", body == null ? null : body.label())));
        return client.createVersion(userId, fileId, body, conditions);
    }
    public ContentData.Content restoreVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) { AppLogger.current().info("file version restore", withConditions(conditions, LogField.fileId(fileId), LogField.versionId(versionId))); return client.restoreVersion(userId, fileId, versionId, conditions); }
    public Void deleteVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) { AppLogger.current().info("file version delete", withConditions(conditions, LogField.fileId(fileId), LogField.versionId(versionId))); return client.deleteVersion(userId, fileId, versionId, conditions); }
    public ContentData.Hits search(UUID userId, UUID projectId, String query, Conditions conditions) { AppLogger.current().info("project search", withConditions(conditions, LogField.projectId(projectId), LogField.param("query", query))); return client.search(userId, projectId, query, conditions); }
    public ContentData.Memos listMemos(UUID userId, UUID projectId, String scope, UUID documentId, Conditions conditions) {
        // scope는 정해진 낱말일 것이나 gateway에 허용 목록이 없어 가려서 남긴다
        AppLogger.current().info("memo list", withConditions(conditions, LogField.projectId(projectId), LogField.documentId(documentId), LogField.param("scope", scope)));
        return client.listMemos(userId, projectId, scope, documentId, conditions);
    }
    public ContentData.Memo createMemo(UUID userId, UUID projectId, ContentData.MemoCreate body, Conditions conditions) {
        // 메모가 붙을 문서는 메모의 범위라 전용 필드(document_id)다
        AppLogger.current().info("memo create", withConditions(conditions, LogField.projectId(projectId), LogField.documentId(body == null ? null : body.documentId()),
                LogField.param("scope", body == null ? null : body.scope()), LogField.param("title", body == null ? null : body.title()),
                LogField.param("body", body == null ? null : body.body())));
        return client.createMemo(userId, projectId, body, conditions);
    }
    public ContentData.Memo updateMemo(UUID userId, UUID memoId, ContentData.MemoUpdate body, Conditions conditions) {
        AppLogger.current().info("memo update", withConditions(conditions, LogField.memoId(memoId), LogField.param("title", body == null ? null : body.title()),
                LogField.param("body", body == null ? null : body.body())));
        return client.updateMemo(userId, memoId, body, conditions);
    }
    public Void deleteMemo(UUID userId, UUID memoId, Conditions conditions) { AppLogger.current().info("memo delete", withConditions(conditions, LogField.memoId(memoId))); return client.deleteMemo(userId, memoId, conditions); }
    public ContentData.Favorites listFavorites(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("favorite list", withConditions(conditions, LogField.projectId(projectId))); return client.listFavorites(userId, projectId, conditions); }
    public ContentData.Favorites addFavorite(UUID userId, UUID projectId, UUID fileId, Conditions conditions) { AppLogger.current().info("favorite add", withConditions(conditions, LogField.projectId(projectId), LogField.fileId(fileId))); return client.addFavorite(userId, projectId, fileId, conditions); }
    public ContentData.Favorites removeFavorite(UUID userId, UUID projectId, UUID fileId, Conditions conditions) { AppLogger.current().info("favorite remove", withConditions(conditions, LogField.projectId(projectId), LogField.fileId(fileId))); return client.removeFavorite(userId, projectId, fileId, conditions); }
    public ContentData.Graph graph(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("graph get", withConditions(conditions, LogField.projectId(projectId))); return client.graph(userId, projectId, conditions); }
    public ContentData.WorkspaceState loadWorkspaceState(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("workspace state get", withConditions(conditions, LogField.projectId(projectId))); return client.loadWorkspaceState(userId, projectId, conditions); }
    public Void saveWorkspaceState(UUID userId, UUID projectId, ContentData.WorkspaceState body, Conditions conditions) {
        AppLogger.current().info("workspace state save", withConditions(conditions, LogField.projectId(projectId), LogField.param("layout", body == null ? null : body.layout())));
        return client.saveWorkspaceState(userId, projectId, body, conditions);
    }
    public ContentData.ImageTicket createImageTicket(UUID userId, UUID projectId, ContentData.ImageTicketRequest body, Conditions conditions) {
        // content_type은 MIME 문자열이고 gateway에 허용 목록이 없어 가린다
        AppLogger.current().info("image upload ticket create", withConditions(conditions, LogField.projectId(projectId),
                LogField.param("file_name", body == null ? null : body.fileName()), LogField.param("content_type", body == null ? null : body.contentType()),
                LogField.param("size_bytes", body == null ? null : body.sizeBytes())));
        return client.createImageTicket(userId, projectId, body, conditions);
    }
    public ContentData.Image completeImage(UUID userId, UUID projectId, UUID imageId, Conditions conditions) { AppLogger.current().info("image upload complete", withConditions(conditions, LogField.projectId(projectId), LogField.imageId(imageId))); return client.completeImage(userId, projectId, imageId, conditions); }
    public ContentData.Image getImage(UUID userId, UUID projectId, UUID imageId, Conditions conditions) { AppLogger.current().info("image get", withConditions(conditions, LogField.projectId(projectId), LogField.imageId(imageId))); return client.getImage(userId, projectId, imageId, conditions); }
    public ContentData.RefreshStart startRefresh(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("graph refresh start", withConditions(conditions, LogField.projectId(projectId))); return client.startRefresh(userId, projectId, conditions); }
    public ContentData.RefreshSummary latestRefresh(UUID userId, UUID projectId, Conditions conditions) { AppLogger.current().info("graph refresh latest get", withConditions(conditions, LogField.projectId(projectId))); return client.latestRefresh(userId, projectId, conditions); }
    public ContentData.RefreshDetail getRefresh(UUID userId, UUID projectId, UUID requestId, Conditions conditions) { AppLogger.current().info("graph refresh get", withConditions(conditions, LogField.projectId(projectId), LogField.refreshRequestId(requestId))); return client.getRefresh(userId, projectId, requestId, conditions); }
    public ContentData.SavedDraft saveRefreshDraft(UUID userId, UUID projectId, UUID requestId, UUID draftId, ContentData.DraftSave body, Conditions conditions) {
        // 양쪽 스냅샷은 본문 바이트 수와 관계 개수만 남긴다(스냅샷 객체는 LogField가 받지 않는다)
        AppLogger.current().info("graph refresh draft save", withConditions(conditions, LogField.projectId(projectId), LogField.refreshRequestId(requestId),
                LogField.refreshDraftId(draftId), LogField.param("draft_revision", body == null ? null : body.draftRevision()),
                LogField.param("left_body", body == null || body.leftSnapshot() == null ? null : body.leftSnapshot().body()),
                LogField.param("left_relations", body == null || body.leftSnapshot() == null ? null : body.leftSnapshot().relations()),
                LogField.param("right_body", body == null || body.rightSnapshot() == null ? null : body.rightSnapshot().body()),
                LogField.param("right_relations", body == null || body.rightSnapshot() == null ? null : body.rightSnapshot().relations())));
        return client.saveRefreshDraft(userId, projectId, requestId, draftId, body, conditions);
    }
    public ContentData.RefreshConfirmed confirmRefresh(UUID userId, UUID projectId, UUID requestId, Conditions conditions) { AppLogger.current().info("graph refresh confirm", withConditions(conditions, LogField.projectId(projectId), LogField.refreshRequestId(requestId))); return client.confirmRefresh(userId, projectId, requestId, conditions); }

    /**
     * 진입 로그 필드 뒤에 조건부 요청 값(Conditions)을 붙인다. If-Match·If-None-Match(ETag)는 문자열이라 가려져 길이만 남고,
     * X-Save-Id는 전용 필드(save_id)다. 비어 있는 칸은 null이라 줄에서 빠진다. conditions가 없으면 받은 필드만 돌려준다.
     */
    private static LogField[] withConditions(Conditions conditions, LogField... fields) {
        if (conditions == null) return fields;
        var all = Arrays.copyOf(fields, fields.length + 3);
        all[fields.length] = LogField.param("if_match", conditions.ifMatch());
        all[fields.length + 1] = LogField.saveId(conditions.saveId());
        all[fields.length + 2] = LogField.param("if_none_match", conditions.ifNoneMatch());
        return all;
    }
}
