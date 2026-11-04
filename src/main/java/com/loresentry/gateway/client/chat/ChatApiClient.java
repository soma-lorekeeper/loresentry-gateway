package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import com.loresentry.gateway.client.SupportedLocale;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.logging.LogFlow;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ChatApiClient {
    public static final String LOCALE_HEADER = "X-User-Locale";
    public static final String DISPLAY_NAME_HEADER = "X-User-Display-Name";
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
                for (var shape : java.util.List.of(tools.jackson.databind.cfg.CoercionInputShape.Integer,
                        tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionInputShape.Boolean))
                    config.setCoercion(shape, tools.jackson.databind.cfg.CoercionAction.Fail);
            }).build();
    private record Error(String code) {}
    private final RestClient client;
    private final ChatStreamTransport stream;

    public ChatApiClient(RestClient chatApiRestClient, ChatStreamTransport chatStreamTransport) {
        this.client = chatApiRestClient;
        this.stream = chatStreamTransport;
    }

    private static int errorStatus(String code) {
        if (code == null) return 0;
        return switch (code) {
            case "INVALID_REQUEST", "CHAT_TITLE_REQUIRED", "CHAT_QUESTION_REQUIRED" -> 400;
            case "CHAT_SESSION_NOT_FOUND", "PROJECT_NOT_FOUND", "SETTING_CHECK_NOT_FOUND", "SETTING_CHECK_FINDING_NOT_FOUND",
                 "SETTING_CHECK_DOCUMENT_NOT_FOUND" -> 404;
            case "CHAT_NOT_LAST_QUESTION", "CHAT_ANSWER_IN_PROGRESS", "CHAT_ANOTHER_ANSWER_IN_PROGRESS", "SETTING_CHECK_IN_PROGRESS" -> 409;
            case "CHAT_CONTEXT_TOO_LARGE" -> 422;
            case "CHAT_DAILY_LIMIT_REACHED" -> 429;
            case "CHAT_MODEL_UNAVAILABLE", "CONTENT_UNAVAILABLE" -> 503;
            default -> 0;
        };
    }

    /**
     * 기대하지 않은 상태의 응답을 실패로 바꾼다. ai-chat 오류 계약(code와 상태가 맞는 오류 본문)이면 그 code, 아니면 잘못된 응답이다.
     * 잘못된 응답으로 바꿀 때는 이유를 warn 한 줄로 남긴다(error.type 고정 이유, error.code는 우리가 아는 code일 때만).
     */
    private static ChatCallFailure failure(int status, MediaType contentType, InputStream body) {
        if (status < 400 || contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            AppLogger.current().warn("ai-chat response rejected",
                    LogField.errorType(status < 400 ? "UNEXPECTED_STATUS" : "UNEXPECTED_CONTENT_TYPE"), LogField.statusCode(status));
            return ChatCallFailure.invalid();
        }
        var error = JSON.readValue(body, Error.class);
        if (error == null || errorStatus(error.code()) != status) {
            // code가 없거나, 모르는 code이거나, 아는 code인데 상태가 계약과 다르다. 모르는 code는 error.code에 남지 않는다
            AppLogger.current().warn("ai-chat response rejected",
                    LogField.upstreamError(error == null ? null : error.code(), true, error != null && errorStatus(error.code()) != 0),
                    LogField.statusCode(status));
            return ChatCallFailure.invalid();
        }
        return new ChatCallFailure(status, error.code(), status == 503 ? "RETRY_LATER" : "NONE");
    }

    private static void profile(ChatData.Profile profile, BiConsumer<String, String> header) {
        if (profile == null) return;
        if (SupportedLocale.valid(profile.locale())) header.accept(LOCALE_HEADER, profile.locale());
        if (profile.displayName() != null)
            header.accept(DISPLAY_NAME_HEADER, UriUtils.encode(profile.displayName(), StandardCharsets.UTF_8));
    }

    /** 본문을 JSON으로 읽다 실패했다. 원인이 IOException이면 본문 읽기 실패(연결 끊김), 아니면 모양이 틀린 응답이다. */
    private static ChatCallFailure decodingFailure(JacksonException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause())
            if (cause instanceof IOException) {
                AppLogger.current().warn("ai-chat response read failed", LogField.errorType(cause.getClass().getName()),
                        LogField.safeStackTrace(cause));
                return ChatCallFailure.unavailable();
            }
        AppLogger.current().warn("ai-chat response rejected", LogField.errorType(failure.getClass().getName()),
                LogField.safeStackTrace(failure));
        return ChatCallFailure.invalid();
    }

    private <T> T call(HttpMethod method, String path, Map<String, ?> variables, UUID userId,
                       ChatData.Profile profile, Object body, Class<T> type, int expected) {
        try {
            var request = client.method(method).uri(path, variables)
                    .header("X-User-Id", Objects.requireNonNull(userId).toString());
            profile(profile, request::header);
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((outgoing, response) -> {
                int status = response.getStatusCode().value();
                if (status == expected && type == Void.class) return null;
                var contentType = response.getHeaders().getContentType();
                if (status != expected) throw failure(status, contentType, response.getBody());
                if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                    AppLogger.current().warn("ai-chat response rejected", LogField.errorType("UNEXPECTED_CONTENT_TYPE"), LogField.statusCode(status));
                    throw ChatCallFailure.invalid();
                }
                return ChatValidation.validate(JSON.readValue(response.getBody(), type));
            });
        } catch (ResourceAccessException failure) {
            // 연결·전송 실패는 UpstreamCallLogInterceptor가 "upstream call failed"로 이미 남긴다
            throw ChatCallFailure.unavailable();
        } catch (JacksonException failure) {
            throw decodingFailure(failure);
        } catch (RestClientException | IllegalArgumentException | NullPointerException failure) {
            // 응답 검증(ChatValidation)에서 빠진 필드·허용하지 않는 값 등. 예외 메시지는 남기지 않는다
            AppLogger.current().warn("ai-chat response rejected", LogField.errorType(failure.getClass().getName()),
                    LogField.safeStackTrace(failure));
            throw ChatCallFailure.invalid();
        }
    }

    public ChatData.Availability availability(UUID userId, UUID projectId) {
        AppLogger.current().info("ai-chat chat availability get call", LogField.projectId(projectId));
        return call(HttpMethod.GET, "/projects/{projectId}/chat/availability", Map.of("projectId", projectId), userId, null, null, ChatData.Availability.class, 200);
    }
    public ChatData.Sessions sessions(UUID userId, UUID projectId) {
        AppLogger.current().info("ai-chat chat session list call", LogField.projectId(projectId));
        return call(HttpMethod.GET, "/projects/{projectId}/chat/sessions", Map.of("projectId", projectId), userId, null, null, ChatData.Sessions.class, 200);
    }
    public ChatData.Session createSession(UUID userId, UUID projectId, ChatData.Profile profile) {
        // 프로필의 표시 이름은 개인정보라 남기지 않는다. locale만 허용 목록으로 거른다
        AppLogger.current().info("ai-chat chat session create call", LogField.projectId(projectId),
                LogField.code("locale", profile == null ? null : profile.locale(), SupportedLocale.VALUES));
        return call(HttpMethod.POST, "/projects/{projectId}/chat/sessions", Map.of("projectId", projectId), userId, profile, null, ChatData.Session.class, 201);
    }
    public ChatData.SearchHits search(UUID userId, UUID projectId, String query) {
        AppLogger.current().info("ai-chat chat search call", LogField.projectId(projectId), LogField.param("query", query));
        return call(HttpMethod.GET, "/projects/{projectId}/chat/search?q={query}", Map.of("projectId", projectId, "query", query == null ? "" : query), userId, null, null, ChatData.SearchHits.class, 200);
    }
    public ChatData.Session renameSession(UUID userId, UUID sessionId, ChatData.Rename body) {
        AppLogger.current().info("ai-chat chat session rename call", LogField.chatSessionId(sessionId),
                LogField.param("title", body == null ? null : body.title()));
        return call(HttpMethod.PATCH, "/chat/sessions/{sessionId}", Map.of("sessionId", sessionId), userId, null, body, ChatData.Session.class, 200);
    }
    public void deleteSession(UUID userId, UUID sessionId) {
        AppLogger.current().info("ai-chat chat session delete call", LogField.chatSessionId(sessionId));
        call(HttpMethod.DELETE, "/chat/sessions/{sessionId}", Map.of("sessionId", sessionId), userId, null, null, Void.class, 204);
    }
    public ChatData.Usage usage(UUID userId) {
        AppLogger.current().info("ai-chat chat usage get call");
        return call(HttpMethod.GET, "/chat/usage", Map.of(), userId, null, null, ChatData.Usage.class, 200);
    }
    public ChatData.Messages messages(UUID userId, UUID sessionId) {
        AppLogger.current().info("ai-chat chat message list call", LogField.chatSessionId(sessionId));
        return call(HttpMethod.GET, "/chat/sessions/{sessionId}/messages", Map.of("sessionId", sessionId), userId, null, null, ChatData.Messages.class, 200);
    }
    public void cancel(UUID userId, UUID sessionId) {
        AppLogger.current().info("ai-chat chat answer cancel call", LogField.chatSessionId(sessionId));
        call(HttpMethod.POST, "/chat/sessions/{sessionId}/cancel", Map.of("sessionId", sessionId), userId, null, null, Void.class, 204);
    }
    public SettingCheckData.Run startSettingCheck(UUID userId, UUID projectId, SettingCheckData.Start body, ChatData.Profile profile) {
        // 검사할 문서는 이번 검사의 대상이라 전용 필드(document_id)로 남긴다. 표시 이름은 남기지 않는다
        AppLogger.current().info("ai-chat setting check start call", LogField.projectId(projectId),
                LogField.documentId(body == null ? null : body.documentId()),
                LogField.code("locale", profile == null ? null : profile.locale(), SupportedLocale.VALUES));
        return call(HttpMethod.POST, "/projects/{projectId}/setting-checks", Map.of("projectId", projectId), userId, profile, body, SettingCheckData.Run.class, 202);
    }
    public SettingCheckData.Summary settingCheckSummary(UUID userId, UUID projectId) {
        AppLogger.current().info("ai-chat setting check summary get call", LogField.projectId(projectId));
        return call(HttpMethod.GET, "/projects/{projectId}/setting-checks/summary", Map.of("projectId", projectId), userId, null, null, SettingCheckData.Summary.class, 200);
    }
    public SettingCheckData.Runs settingChecks(UUID userId, UUID projectId, UUID documentId) {
        AppLogger.current().info("ai-chat setting check list call", LogField.projectId(projectId), LogField.documentId(documentId));
        return call(HttpMethod.GET, "/projects/{projectId}/setting-checks?document_id={documentId}", Map.of("projectId", projectId, "documentId", documentId), userId, null, null, SettingCheckData.Runs.class, 200);
    }
    public SettingCheckData.Decisions settingCheckVerdicts(UUID userId, UUID projectId) {
        AppLogger.current().info("ai-chat setting check verdict list call", LogField.projectId(projectId));
        return call(HttpMethod.GET, "/projects/{projectId}/setting-checks/verdicts", Map.of("projectId", projectId), userId, null, null, SettingCheckData.Decisions.class, 200);
    }
    public SettingCheckData.Run settingCheck(UUID userId, UUID runId) {
        AppLogger.current().info("ai-chat setting check get call", LogField.settingCheckRunId(runId));
        var run = call(HttpMethod.GET, "/setting-checks/{runId}", Map.of("runId", runId), userId, null, null, SettingCheckData.Run.class, 200);
        if (run.findings() == null) {
            // 200으로 받은 검사 결과에 findings가 없다(call은 기대한 상태 200일 때만 값을 돌려준다)
            AppLogger.current().warn("ai-chat response rejected", LogField.settingCheckRunId(runId), LogField.errorType("MISSING_FIELD"),
                    LogField.statusCode(200));
            throw ChatCallFailure.invalid();
        }
        return run;
    }
    public void cancelSettingCheck(UUID userId, UUID runId) {
        AppLogger.current().info("ai-chat setting check cancel call", LogField.settingCheckRunId(runId));
        call(HttpMethod.POST, "/setting-checks/{runId}/cancel", Map.of("runId", runId), userId, null, null, Void.class, 204);
    }
    public SettingCheckData.Finding setSettingCheckVerdict(UUID userId, UUID findingId, SettingCheckData.Verdict body) {
        // 판정은 정해 둔 낱말이라 그대로, 사유는 사용자가 쓴 글이라 길이만 남긴다
        AppLogger.current().info("ai-chat setting check verdict set call", LogField.settingCheckFindingId(findingId),
                LogField.code("verdict", body == null ? null : body.verdict(), SettingCheckData.VERDICTS),
                LogField.param("reason", body == null ? null : body.reason()));
        return call(HttpMethod.PUT, "/setting-check-findings/{findingId}/verdict", Map.of("findingId", findingId), userId, null, body, SettingCheckData.Finding.class, 200);
    }
    public SettingCheckData.Finding clearSettingCheckVerdict(UUID userId, UUID findingId) {
        AppLogger.current().info("ai-chat setting check verdict clear call", LogField.settingCheckFindingId(findingId));
        return call(HttpMethod.DELETE, "/setting-check-findings/{findingId}/verdict", Map.of("findingId", findingId), userId, null, null, SettingCheckData.Finding.class, 200);
    }
    public void deleteUserData(UUID userId) {
        AppLogger.current().info("ai-chat user data delete call");
        call(HttpMethod.DELETE, "/users/me/data", Map.of(), userId, null, null, Void.class, 204);
    }
    public void deleteProjectData(UUID userId, UUID projectId) {
        AppLogger.current().info("ai-chat project data delete call", LogField.projectId(projectId));
        call(HttpMethod.DELETE, "/projects/{projectId}/chat/data", Map.of("projectId", projectId), userId, null, null, Void.class, 204);
    }

    public ChatStream send(UUID userId, UUID sessionId, ChatData.SendInput body, ChatData.Profile profile) {
        // 질문·인용문은 길이만 남긴다. 보고 있던 문서·인용한 문서·다시 묻는 질문은 참조만 하므로 역할을 붙인 param이다
        AppLogger.current().info("ai-chat chat message send call", LogField.chatSessionId(sessionId),
                LogField.param("content", body == null ? null : body.content()),
                LogField.code("intent", body == null ? null : body.intent(), ChatData.INTENTS),
                LogField.param("focus_file_id", body == null ? null : body.focusFileId()),
                LogField.param("quote_file_id", body == null || body.quote() == null ? null : body.quote().fileId()),
                LogField.param("quote_text", body == null || body.quote() == null ? null : body.quote().text()),
                LogField.param("replaced_message_id", body == null ? null : body.replaces()),
                LogField.param("client_message_id", body == null ? null : body.clientMessageId()),
                LogField.code("locale", profile == null ? null : profile.locale(), SupportedLocale.VALUES));
        HttpPost request;
        // 호출 결과 줄의 대상 주소. url.full은 LogField가 쿼리를 빼고 남긴다
        URI uri;
        try {
            uri = stream.uri("/chat/sessions/{sessionId}/messages", Map.of("sessionId", Objects.requireNonNull(sessionId)));
            request = new HttpPost(uri);
            request.setHeader("X-User-Id", Objects.requireNonNull(userId).toString());
            request.setHeader("Accept", "text/event-stream, application/json");
            profile(profile, request::setHeader);
            stream.trace(request::setHeader);
            request.setEntity(new ByteArrayEntity(JSON.writeValueAsBytes(body), ContentType.APPLICATION_JSON));
        } catch (JacksonException | IllegalArgumentException | NullPointerException failure) {
            // 보내기 전에 요청을 만들지 못했다(값 누락·직렬화 실패). 예외 메시지는 남기지 않는다
            AppLogger.current().warn("ai-chat stream request build failed", LogField.chatSessionId(sessionId),
                    LogField.errorType(failure.getClass().getName()), LogField.safeStackTrace(failure));
            throw ChatCallFailure.invalid();
        }
        // SSE 요청은 RestClient를 거치지 않아 UpstreamCallLogInterceptor가 남기지 않는다. 같은 문구(upstream call completed·failed)와
        // 주소 필드로 결과 줄을 남겨 한 번의 검색으로 함께 찾는다. 상태를 받은 시점까지의 결과다(본문 스트리밍은 relay 종료 줄에 있다)
        ClassicHttpResponse response;
        try {
            response = LogFlow.call("upstream", uri, call -> {
                call.with(LogField.chatSessionId(sessionId));
                var answered = stream.http().executeOpen(null, request, null);
                call.status(answered.getCode());
                return answered;
            });
        } catch (IOException failure) {
            UpstreamChatStream.abort(request, null);
            throw ChatCallFailure.unavailable();
        }
        int status = response.getCode();
        boolean streaming = false;
        try {
            var entity = response.getEntity();
            var contentType = entity == null || entity.getContentType() == null ? null : MediaType.parseMediaType(entity.getContentType());
            if (status == 200) {
                if (contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                    AppLogger.current().warn("ai-chat response rejected", LogField.chatSessionId(sessionId),
                            LogField.errorType("UNEXPECTED_CONTENT_TYPE"), LogField.statusCode(status));
                    throw ChatCallFailure.invalid();
                }
                var opened = new UpstreamChatStream(request, response, entity.getContent(), sessionId);
                streaming = true;
                return opened;
            }
            throw failure(status, contentType, entity == null ? InputStream.nullInputStream() : entity.getContent());
        } catch (IOException failure) {
            AppLogger.current().warn("ai-chat response read failed", LogField.chatSessionId(sessionId),
                    LogField.errorType(failure.getClass().getName()), LogField.safeStackTrace(failure), LogField.statusCode(status));
            throw ChatCallFailure.unavailable();
        } catch (JacksonException failure) {
            throw decodingFailure(failure);
        } catch (IllegalArgumentException | NullPointerException failure) {
            // Content-Type을 읽지 못했거나 응답 모양이 틀렸다. 예외 메시지는 남기지 않는다
            AppLogger.current().warn("ai-chat response rejected", LogField.chatSessionId(sessionId),
                    LogField.errorType(failure.getClass().getName()), LogField.safeStackTrace(failure), LogField.statusCode(status));
            throw ChatCallFailure.invalid();
        } finally {
            if (!streaming) UpstreamChatStream.abort(request, response);
        }
    }
}
