package com.loresentry.gateway.client.content;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.core.JacksonException;
import org.springframework.web.client.ResourceAccessException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.client.SupportedLocale;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;

@Component
public class ContentApiClient {
    private final RestClient client;
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
                for (var shape : java.util.List.of(tools.jackson.databind.cfg.CoercionInputShape.Integer,
                        tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionInputShape.Boolean))
                    config.setCoercion(shape, tools.jackson.databind.cfg.CoercionAction.Fail);
            }).build();
    /**
     * Content 오류 본문. code·message·next_action 옆에 code 마다 상세 값이 붙는다.
     * current 는 DOCUMENT_CONFLICT(문서)와 REFRESH_DRAFT_CONFLICT(작업본)의 모양이 달라 code 를 본 뒤 변환한다.
     */
    private record Error(String code, String message, @JsonProperty("next_action") String nextAction,
                         tools.jackson.databind.JsonNode current, ContentData.Snapshot base, List<UUID> drafts,
                         List<ContentData.ChangedDocument> documents, List<Map<String, String>> problems) {}
    private static int errorStatus(String code) {
        if (code == null) return 0;
        return switch (code) {
            case "INVALID_REQUEST", "INVALID_PROJECT_NAME", "INVALID_PROJECT_DESCRIPTION", "INVALID_FILE_TITLE",
                    "INVALID_FILE_LOCATION", "INVALID_RELATION_TARGET", "INVALID_MEMO",
                    "INVALID_UPLOAD_REQUEST", "INVALID_FEEDBACK" -> 400;
            case "PROJECT_NOT_FOUND", "FILE_NOT_FOUND", "VERSION_NOT_FOUND", "NOT_FOUND",
                    "MEMO_NOT_FOUND", "IMAGE_NOT_FOUND", "REFRESH_NOT_FOUND", "REFRESH_DRAFT_NOT_FOUND" -> 404;
            case "PROJECT_NAME_TAKEN", "PROJECT_NOT_TRASHED", "FILE_TITLE_TAKEN", "FILE_NOT_TRASHED",
                    "DOCUMENT_LOCKED", "DOCUMENT_CONFLICT", "OBJECT_NOT_UPLOADED",
                    // 최신화가 문서를 잠갔거나, 진행·검토 중이거나, 작업본을 저장·확정할 수 없는 상태다.
                    "DOCUMENT_REFRESH_LOCKED", "REFRESH_IN_PROGRESS", "REFRESH_REVIEW_PENDING", "REFRESH_NOT_READY",
                    "REFRESH_DRAFT_CONFLICT", "REFRESH_DRAFTS_UNMATCHED", "REFRESH_DOCUMENTS_CHANGED",
                    "REFRESH_DRAFTS_INVALID" -> 409;
            case "FEEDBACK_RATE_LIMITED" -> 429;
            case "INTERNAL_ERROR" -> 500;
            // 최신화 입력을 올리지 못했다. 아무것도 기록하지 않았으므로 다시 요청할 수 있다.
            case "REFRESH_UNAVAILABLE" -> 503;
            default -> 0;
        };
    }
    /** Content 가 보내는 next_action. 잠시 뒤 다시 하면 되는 오류만 RETRY_LATER 다. */
    private static String errorAction(String code) {
        if (code == null) return "NONE";
        return switch (code) {
            case "FEEDBACK_RATE_LIMITED", "REFRESH_IN_PROGRESS", "REFRESH_UNAVAILABLE" -> "RETRY_LATER";
            default -> "NONE";
        };
    }
    public ContentApiClient(RestClient contentApiRestClient) { this.client = contentApiRestClient; }
    public record Conditions(String ifMatch, String saveId, String ifNoneMatch) {}

    /**
     * 진입 로그 필드에 조건 헤더를 더한다. If-Match·If-None-Match는 ETag 문자열이라 param으로 가리고(길이만 남는다),
     * X-Save-Id는 문서 저장의 중복 방지 키라 전용 필드(save_id)로 남긴다. 값이 없는(null) 필드는 AppLogger가 건너뛴다.
     */
    private static LogField[] withConditions(Conditions conditions, LogField... fields) {
        if (conditions == null) return fields;
        var all = java.util.Arrays.copyOf(fields, fields.length + 3);
        all[fields.length] = LogField.param("if_match", conditions.ifMatch());
        all[fields.length + 1] = LogField.saveId(conditions.saveId());
        all[fields.length + 2] = LogField.param("if_none_match", conditions.ifNoneMatch());
        return all;
    }

    private <T> T call(String method, String path, Map<String, ?> variables, UUID userId,
                       Object body, Class<T> type, int expected, Conditions conditions) {
        return call(method, path, variables, userId, body, type, Set.of(expected), conditions);
    }
    /** 성공 상태가 둘 이상인 호출(최신화 요청의 200·202)을 위해 기대 상태를 집합으로 받는다. */
    private <T> T call(String method, String path, Map<String, ?> variables, UUID userId,
                       Object body, Class<T> type, Set<Integer> expected, Conditions conditions) {
        try {
            var request = client.method(HttpMethod.valueOf(method)).uri(path, variables)
                    .header("X-User-Id", Objects.requireNonNull(userId).toString());
            if (conditions != null) {
                if (conditions.ifMatch() != null) request.header("If-Match", conditions.ifMatch());
                if (conditions.saveId() != null) request.header("X-Save-Id", conditions.saveId());
                if (conditions.ifNoneMatch() != null) request.header("If-None-Match", conditions.ifNoneMatch());
            }
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((outgoing, response) -> {
                int status = response.getStatusCode().value();
                if (expected.contains(status) && type == Void.class) return null;
                var contentType = response.getHeaders().getContentType();
                if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType))
                    throw ContentCallFailure.invalid();
                if (expected.contains(status)) return ContentValidation.validate(JSON.readValue(response.getBody(), type));
                if (status < 400) throw ContentCallFailure.invalid();
                var error = JSON.readValue(response.getBody(), Error.class);
                if (error == null || errorStatus(error.code()) != status || !errorAction(error.code()).equals(error.nextAction())
                        || error.message() == null) throw ContentCallFailure.invalid();
                if ("DOCUMENT_CONFLICT".equals(error.code())) {
                    var current = ContentValidation.validate(JSON.treeToValue(error.current(), ContentData.Content.class));
                    if (error.base() != null) ContentValidation.validate(error.base());
                    throw new ContentCallFailure(status, error.code(), current, error.base());
                }
                throw new ContentCallFailure(status, error.code(), details(error));
            });
        } catch (ResourceAccessException failure) {
            throw ContentCallFailure.unavailable();
        } catch (JacksonException failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause())
                if (cause instanceof java.io.IOException) throw ContentCallFailure.unavailable();
            throw ContentCallFailure.invalid();
        } catch (RestClientException | IllegalArgumentException | NullPointerException failure) {
            throw ContentCallFailure.invalid();
        }
    }

    /** 최신화 오류의 상세 값을 code 에 맞춰 꺼낸다. 있어야 할 값이 없거나 모양이 틀리면 잘못된 응답이다. */
    private static ContentCallFailure.Details details(Error error) {
        var details = switch (error.code()) {
            case "REFRESH_DRAFT_CONFLICT" -> new ContentCallFailure.Details(
                    Objects.requireNonNull(JSON.treeToValue(error.current(), ContentData.DraftConflict.class)), null, null, null);
            case "REFRESH_DRAFTS_UNMATCHED" -> new ContentCallFailure.Details(null, Objects.requireNonNull(error.drafts()), null, null);
            case "REFRESH_DOCUMENTS_CHANGED" -> new ContentCallFailure.Details(null, null, Objects.requireNonNull(error.documents()), null);
            case "REFRESH_DRAFTS_INVALID" -> new ContentCallFailure.Details(null, null, null, Objects.requireNonNull(error.problems()));
            default -> null;
        };
        return details == null ? null : ContentValidation.validate(details);
    }

    public Void recordWithdrawalSurvey(UUID userId, ContentData.WithdrawalSurvey body) {
        // 탈퇴 사유는 고른 항목 수만, 자유 의견은 길이만 남는다
        AppLogger.current().info("content withdrawal survey record call", LogField.param("reasons", body == null ? null : body.reasons()),
                LogField.param("comment", body == null ? null : body.comment()));
        return call("POST", "/withdrawal-surveys", Map.of(), userId, body, Void.class, 204, null);
    }
    public ContentData.FeedbackCreated createFeedback(UUID userId, ContentData.FeedbackInput body, Conditions conditions) {
        AppLogger.current().info("content feedback create call", withConditions(conditions,
                LogField.param("category", body == null ? null : body.category()), LogField.param("message", body == null ? null : body.message()),
                LogField.param("page", body == null ? null : body.page()), LogField.param("client", body == null ? null : body.client())));
        return call("POST", "/feedback", Map.of(), userId, body, ContentData.FeedbackCreated.class, 201, conditions);
    }
    public ContentData.Projects listProjects(UUID userId, Conditions conditions) {
        AppLogger.current().info("content project list call", withConditions(conditions));
        return call("GET", "/projects", Map.of(), userId, null, ContentData.Projects.class, 200, conditions);
    }
    public ContentData.Projects listProjectTrash(UUID userId, Conditions conditions) {
        AppLogger.current().info("content project trash list call", withConditions(conditions));
        return call("GET", "/projects/trash", Map.of(), userId, null, ContentData.Projects.class, 200, conditions);
    }
    public ContentData.Project createProject(UUID userId, ContentData.ProjectInput body, Conditions conditions) {
        AppLogger.current().info("content project create call", withConditions(conditions,
                LogField.param("name", body == null ? null : body.name()), LogField.param("description", body == null ? null : body.description())));
        return call("POST", "/projects", Map.of(), userId, body, ContentData.Project.class, 201, conditions);
    }
    public ContentData.Project createSampleProject(UUID userId, String locale, Conditions conditions) {
        AppLogger.current().info("content sample project create call", withConditions(conditions, LogField.code("locale", locale, SupportedLocale.VALUES)));
        if (!SupportedLocale.valid(locale)) return call("POST", "/projects/sample", Map.of(), userId, null, ContentData.Project.class, 201, conditions);
        return call("POST", "/projects/sample?locale={locale}", Map.of("locale", locale), userId, null, ContentData.Project.class, 201, conditions);
    }
    public ContentData.Project getProject(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content project get call", withConditions(conditions, LogField.projectId(projectId)));
        return call("GET", "/projects/{projectId}", Map.of("projectId", projectId), userId, null, ContentData.Project.class, 200, conditions);
    }
    public ContentData.Project updateProject(UUID userId, UUID projectId, ContentData.ProjectInput body, Conditions conditions) {
        AppLogger.current().info("content project update call", withConditions(conditions, LogField.projectId(projectId),
                LogField.param("name", body == null ? null : body.name()), LogField.param("description", body == null ? null : body.description())));
        return call("PATCH", "/projects/{projectId}", Map.of("projectId", projectId), userId, body, ContentData.Project.class, 200, conditions);
    }
    public Void trashProject(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content project trash call", withConditions(conditions, LogField.projectId(projectId)));
        return call("POST", "/projects/{projectId}/trash", Map.of("projectId", projectId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Project restoreProject(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content project restore call", withConditions(conditions, LogField.projectId(projectId)));
        return call("POST", "/projects/{projectId}/restore", Map.of("projectId", projectId), userId, null, ContentData.Project.class, 200, conditions);
    }
    public Void deleteProject(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content project delete call", withConditions(conditions, LogField.projectId(projectId)));
        return call("DELETE", "/projects/{projectId}", Map.of("projectId", projectId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Tree tree(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content file tree get call", withConditions(conditions, LogField.projectId(projectId)));
        return call("GET", "/projects/{projectId}/files", Map.of("projectId", projectId), userId, null, ContentData.Tree.class, 200, conditions);
    }
    public ContentData.Trash fileTrash(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content file trash list call", withConditions(conditions, LogField.projectId(projectId)));
        return call("GET", "/projects/{projectId}/files/trash", Map.of("projectId", projectId), userId, null, ContentData.Trash.class, 200, conditions);
    }
    public FileCreated createFile(UUID userId, UUID projectId, ContentData.FileCreate body, Conditions conditions) {
        // 새 문서가 들어갈 회차는 그 문서가 속할 범위라 전용 필드(episode_id)로 남긴다
        AppLogger.current().info("content file create call", withConditions(conditions, LogField.projectId(projectId),
                LogField.episodeId(body == null ? null : body.episodeId()), LogField.param("kind", body == null ? null : body.kind()),
                LogField.param("title", body == null ? null : body.title()), LogField.param("folder_code", body == null ? null : body.folderCode())));
        if (body != null && "episode".equals(body.kind())) return new FileCreated(null, call("POST", "/projects/{projectId}/files", Map.of("projectId", projectId), userId, body, ContentData.Episode.class, 201, conditions));
        return new FileCreated(call("POST", "/projects/{projectId}/files", Map.of("projectId", projectId), userId, body, ContentData.Document.class, 201, conditions), null);
    }
    public ContentData.Document renameFile(UUID userId, UUID fileId, ContentData.Rename body, Conditions conditions) {
        AppLogger.current().info("content file rename call", withConditions(conditions, LogField.fileId(fileId),
                LogField.param("title", body == null ? null : body.title())));
        return call("PATCH", "/files/{fileId}", Map.of("fileId", fileId), userId, body, ContentData.Document.class, 200, conditions);
    }
    public ContentData.Document moveFile(UUID userId, UUID fileId, ContentData.Move body, Conditions conditions) {
        // 옮길 위치(분류·회차)와 앞에 둘 파일은 참조만 하므로 역할을 붙인 param으로 남긴다
        AppLogger.current().info("content file move call", withConditions(conditions, LogField.fileId(fileId),
                LogField.param("target_folder_code", body == null ? null : body.folderCode()),
                LogField.param("target_episode_id", body == null ? null : body.episodeId()),
                LogField.param("before_file_id", body == null ? null : body.beforeFileId())));
        return call("PATCH", "/files/{fileId}/position", Map.of("fileId", fileId), userId, body, ContentData.Document.class, 200, conditions);
    }
    public Void trashFile(UUID userId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content file trash call", withConditions(conditions, LogField.fileId(fileId)));
        return call("POST", "/files/{fileId}/trash", Map.of("fileId", fileId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Document restoreFile(UUID userId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content file restore call", withConditions(conditions, LogField.fileId(fileId)));
        return call("POST", "/files/{fileId}/restore", Map.of("fileId", fileId), userId, null, ContentData.Document.class, 200, conditions);
    }
    public Void deleteFile(UUID userId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content file delete call", withConditions(conditions, LogField.fileId(fileId)));
        return call("DELETE", "/files/{fileId}", Map.of("fileId", fileId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Episode renameEpisode(UUID userId, UUID episodeId, ContentData.Rename body, Conditions conditions) {
        AppLogger.current().info("content episode rename call", withConditions(conditions, LogField.episodeId(episodeId),
                LogField.param("title", body == null ? null : body.title())));
        return call("PATCH", "/episodes/{episodeId}", Map.of("episodeId", episodeId), userId, body, ContentData.Episode.class, 200, conditions);
    }
    public Void deleteEpisode(UUID userId, UUID episodeId, Conditions conditions) {
        AppLogger.current().info("content episode delete call", withConditions(conditions, LogField.episodeId(episodeId)));
        return call("DELETE", "/episodes/{episodeId}", Map.of("episodeId", episodeId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Content getContent(UUID userId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content file content get call", withConditions(conditions, LogField.fileId(fileId)));
        return call("GET", "/files/{fileId}/content", Map.of("fileId", fileId), userId, null, ContentData.Content.class, 200, conditions);
    }
    public ContentData.Content saveContent(UUID userId, UUID fileId, ContentData.Snapshot body, Conditions conditions) {
        // 원고는 남기지 않는다: 본문은 바이트 수, 제목·Markdown 본문은 길이, 속성·관계는 개수만 남는다
        AppLogger.current().info("content file content save call", withConditions(conditions, LogField.fileId(fileId),
                LogField.param("title", body == null ? null : body.title()), LogField.param("body", body == null ? null : body.body()),
                LogField.param("legacy_body_md", body == null ? null : body.legacyBodyMd()),
                LogField.param("properties", body == null ? null : body.properties()), LogField.param("relations", body == null ? null : body.relations())));
        return call("PUT", "/files/{fileId}/content", Map.of("fileId", fileId), userId, body, ContentData.Content.class, 200, conditions);
    }
    public ContentData.Document setDocumentImage(UUID userId, UUID fileId, ContentData.DocumentImage body, Conditions conditions) {
        // 붙일 이미지는 이 줄의 대상(문서)이 참조하는 상대라 역할을 붙인 param으로 남긴다
        AppLogger.current().info("content file image set call", withConditions(conditions, LogField.fileId(fileId),
                LogField.param("attached_image_id", body == null ? null : body.imageId())));
        return call("PUT", "/files/{fileId}/image", Map.of("fileId", fileId), userId, body, ContentData.Document.class, 200, conditions);
    }
    public Void clearDocumentImage(UUID userId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content file image clear call", withConditions(conditions, LogField.fileId(fileId)));
        return call("DELETE", "/files/{fileId}/image", Map.of("fileId", fileId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Content setLocked(UUID userId, UUID fileId, ContentData.Lock body, Conditions conditions) {
        AppLogger.current().info("content file lock set call", withConditions(conditions, LogField.fileId(fileId),
                LogField.param("locked", body == null ? null : body.locked())));
        return call("PUT", "/files/{fileId}/lock", Map.of("fileId", fileId), userId, body, ContentData.Content.class, 200, conditions);
    }
    public ContentData.Versions versions(UUID userId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content file version list call", withConditions(conditions, LogField.fileId(fileId)));
        return call("GET", "/files/{fileId}/versions", Map.of("fileId", fileId), userId, null, ContentData.Versions.class, 200, conditions);
    }
    public ContentData.Version createVersion(UUID userId, UUID fileId, ContentData.NamedVersion body, Conditions conditions) {
        AppLogger.current().info("content file version create call", withConditions(conditions, LogField.fileId(fileId),
                LogField.param("label", body == null ? null : body.label())));
        return call("POST", "/files/{fileId}/versions", Map.of("fileId", fileId), userId, body, ContentData.Version.class, 201, conditions);
    }
    public ContentData.Content restoreVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) {
        AppLogger.current().info("content file version restore call", withConditions(conditions, LogField.fileId(fileId), LogField.versionId(versionId)));
        return call("POST", "/files/{fileId}/versions/{versionId}/restore", Map.of("fileId", fileId, "versionId", versionId), userId, null, ContentData.Content.class, 200, conditions);
    }
    public Void deleteVersion(UUID userId, UUID fileId, UUID versionId, Conditions conditions) {
        AppLogger.current().info("content file version delete call", withConditions(conditions, LogField.fileId(fileId), LogField.versionId(versionId)));
        return call("DELETE", "/files/{fileId}/versions/{versionId}", Map.of("fileId", fileId, "versionId", versionId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Hits search(UUID userId, UUID projectId, String query, Conditions conditions) {
        AppLogger.current().info("content project search call", withConditions(conditions, LogField.projectId(projectId), LogField.param("query", query)));
        return call("GET", "/projects/{projectId}/search?q={query}", Map.of("projectId", projectId, "query", query == null ? "" : query), userId, null, ContentData.Hits.class, 200, conditions);
    }
    public ContentData.Memos listMemos(UUID userId, UUID projectId, String scope, UUID documentId, Conditions conditions) {
        AppLogger.current().info("content memo list call", withConditions(conditions, LogField.projectId(projectId), LogField.documentId(documentId),
                LogField.param("scope", scope)));
        if (documentId == null) return call("GET", "/projects/{projectId}/memos?scope={scope}", Map.of("projectId", projectId, "scope", scope == null ? "" : scope), userId, null, ContentData.Memos.class, 200, conditions);
        return call("GET", "/projects/{projectId}/memos?scope={scope}&document_id={documentId}", Map.of("projectId", projectId, "scope", scope == null ? "" : scope, "documentId", documentId), userId, null, ContentData.Memos.class, 200, conditions);
    }
    public ContentData.Memo createMemo(UUID userId, UUID projectId, ContentData.MemoCreate body, Conditions conditions) {
        // 메모가 붙는 문서는 메모가 속할 범위라 전용 필드(document_id)로 남긴다
        AppLogger.current().info("content memo create call", withConditions(conditions, LogField.projectId(projectId),
                LogField.documentId(body == null ? null : body.documentId()), LogField.param("scope", body == null ? null : body.scope()),
                LogField.param("title", body == null ? null : body.title()), LogField.param("body", body == null ? null : body.body())));
        return call("POST", "/projects/{projectId}/memos", Map.of("projectId", projectId), userId, body, ContentData.Memo.class, 201, conditions);
    }
    public ContentData.Memo updateMemo(UUID userId, UUID memoId, ContentData.MemoUpdate body, Conditions conditions) {
        AppLogger.current().info("content memo update call", withConditions(conditions, LogField.memoId(memoId),
                LogField.param("title", body == null ? null : body.title()), LogField.param("body", body == null ? null : body.body())));
        return call("PATCH", "/memos/{memoId}", Map.of("memoId", memoId), userId, body, ContentData.Memo.class, 200, conditions);
    }
    public Void deleteMemo(UUID userId, UUID memoId, Conditions conditions) {
        AppLogger.current().info("content memo delete call", withConditions(conditions, LogField.memoId(memoId)));
        return call("DELETE", "/memos/{memoId}", Map.of("memoId", memoId), userId, null, Void.class, 204, conditions);
    }
    public ContentData.Favorites listFavorites(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content favorite list call", withConditions(conditions, LogField.projectId(projectId)));
        return call("GET", "/projects/{projectId}/favorites", Map.of("projectId", projectId), userId, null, ContentData.Favorites.class, 200, conditions);
    }
    public ContentData.Favorites addFavorite(UUID userId, UUID projectId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content favorite add call", withConditions(conditions, LogField.projectId(projectId), LogField.fileId(fileId)));
        return call("PUT", "/projects/{projectId}/favorites/{fileId}", Map.of("projectId", projectId, "fileId", fileId), userId, null, ContentData.Favorites.class, 200, conditions);
    }
    public ContentData.Favorites removeFavorite(UUID userId, UUID projectId, UUID fileId, Conditions conditions) {
        AppLogger.current().info("content favorite remove call", withConditions(conditions, LogField.projectId(projectId), LogField.fileId(fileId)));
        return call("DELETE", "/projects/{projectId}/favorites/{fileId}", Map.of("projectId", projectId, "fileId", fileId), userId, null, ContentData.Favorites.class, 200, conditions);
    }
    public ContentData.Graph graph(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content graph get call", withConditions(conditions, LogField.projectId(projectId)));
        return call("GET", "/projects/{projectId}/graph", Map.of("projectId", projectId), userId, null, ContentData.Graph.class, 200, conditions);
    }
    public ContentData.WorkspaceState loadWorkspaceState(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content workspace state get call", withConditions(conditions, LogField.projectId(projectId)));
        return call("GET", "/projects/{projectId}/workspace-state", Map.of("projectId", projectId), userId, null, ContentData.WorkspaceState.class, 200, conditions);
    }
    public Void saveWorkspaceState(UUID userId, UUID projectId, ContentData.WorkspaceState body, Conditions conditions) {
        AppLogger.current().info("content workspace state save call", withConditions(conditions, LogField.projectId(projectId),
                LogField.param("layout", body == null ? null : body.layout())));
        return call("PUT", "/projects/{projectId}/workspace-state", Map.of("projectId", projectId), userId, body, Void.class, 204, conditions);
    }
    public ContentData.ImageTicket createImageTicket(UUID userId, UUID projectId, ContentData.ImageTicketRequest body, Conditions conditions) {
        AppLogger.current().info("content image upload ticket create call", withConditions(conditions, LogField.projectId(projectId),
                LogField.param("file_name", body == null ? null : body.fileName()), LogField.param("content_type", body == null ? null : body.contentType()),
                LogField.param("size_bytes", body == null ? null : body.sizeBytes())));
        return call("POST", "/projects/{projectId}/images", Map.of("projectId", projectId), userId, body, ContentData.ImageTicket.class, 201, conditions);
    }
    public ContentData.Image completeImage(UUID userId, UUID projectId, UUID imageId, Conditions conditions) {
        AppLogger.current().info("content image upload complete call", withConditions(conditions, LogField.projectId(projectId), LogField.imageId(imageId)));
        return call("POST", "/projects/{projectId}/images/{imageId}/complete", Map.of("projectId", projectId, "imageId", imageId), userId, null, ContentData.Image.class, 200, conditions);
    }
    public ContentData.Image getImage(UUID userId, UUID projectId, UUID imageId, Conditions conditions) {
        AppLogger.current().info("content image get call", withConditions(conditions, LogField.projectId(projectId), LogField.imageId(imageId)));
        return call("GET", "/projects/{projectId}/images/{imageId}", Map.of("projectId", projectId, "imageId", imageId), userId, null, ContentData.Image.class, 200, conditions);
    }
    /** 본문 없이 요청한다. 문서를 보냈으면 202(GENERATING), 바뀐 문서가 없으면 200(NO_CHANGES)이다. */
    public ContentData.RefreshStart startRefresh(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content graph refresh start call", withConditions(conditions, LogField.projectId(projectId)));
        return call("POST", "/projects/{projectId}/graph-refresh", Map.of("projectId", projectId), userId, null, ContentData.RefreshStart.class, Set.of(200, 202), conditions);
    }
    public ContentData.RefreshSummary latestRefresh(UUID userId, UUID projectId, Conditions conditions) {
        AppLogger.current().info("content graph refresh latest get call", withConditions(conditions, LogField.projectId(projectId)));
        return call("GET", "/projects/{projectId}/graph-refresh", Map.of("projectId", projectId), userId, null, ContentData.RefreshSummary.class, 200, conditions);
    }
    public ContentData.RefreshDetail getRefresh(UUID userId, UUID projectId, UUID requestId, Conditions conditions) {
        AppLogger.current().info("content graph refresh get call", withConditions(conditions, LogField.projectId(projectId),
                LogField.refreshRequestId(requestId)));
        return call("GET", "/projects/{projectId}/graph-refresh/{requestId}", Map.of("projectId", projectId, "requestId", requestId), userId, null, ContentData.RefreshDetail.class, 200, conditions);
    }
    public ContentData.SavedDraft saveRefreshDraft(UUID userId, UUID projectId, UUID requestId, UUID draftId, ContentData.DraftSave body, Conditions conditions) {
        // 작업본 두 쪽(left·right)은 원고라 본문 바이트 수만 남긴다. NEW 작업본은 한쪽이 없을 수 있다
        AppLogger.current().info("content graph refresh draft save call", withConditions(conditions, LogField.projectId(projectId),
                LogField.refreshRequestId(requestId), LogField.refreshDraftId(draftId),
                LogField.param("draft_revision", body == null ? null : body.draftRevision()),
                LogField.param("left_body", body == null || body.leftSnapshot() == null ? null : body.leftSnapshot().body()),
                LogField.param("right_body", body == null || body.rightSnapshot() == null ? null : body.rightSnapshot().body())));
        return call("PATCH", "/projects/{projectId}/graph-refresh/{requestId}/drafts/{draftId}", Map.of("projectId", projectId, "requestId", requestId, "draftId", draftId), userId, body, ContentData.SavedDraft.class, 200, conditions);
    }
    public ContentData.RefreshConfirmed confirmRefresh(UUID userId, UUID projectId, UUID requestId, Conditions conditions) {
        AppLogger.current().info("content graph refresh confirm call", withConditions(conditions, LogField.projectId(projectId),
                LogField.refreshRequestId(requestId)));
        return call("POST", "/projects/{projectId}/graph-refresh/{requestId}/confirm", Map.of("projectId", projectId, "requestId", requestId), userId, null, ContentData.RefreshConfirmed.class, 200, conditions);
    }
}
