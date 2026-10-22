package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import com.loresentry.gateway.client.SupportedLocale;
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
            case "CHAT_SESSION_NOT_FOUND", "PROJECT_NOT_FOUND" -> 404;
            case "CHAT_NOT_LAST_QUESTION", "CHAT_ANSWER_IN_PROGRESS", "CHAT_ANOTHER_ANSWER_IN_PROGRESS" -> 409;
            case "CHAT_CONTEXT_TOO_LARGE" -> 422;
            case "CHAT_DAILY_LIMIT_REACHED" -> 429;
            case "CHAT_MODEL_UNAVAILABLE", "CONTENT_UNAVAILABLE" -> 503;
            default -> 0;
        };
    }

    private static ChatCallFailure failure(int status, MediaType contentType, InputStream body) {
        if (status < 400 || contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType))
            return ChatCallFailure.invalid();
        var error = JSON.readValue(body, Error.class);
        if (error == null || errorStatus(error.code()) != status) return ChatCallFailure.invalid();
        return new ChatCallFailure(status, error.code(), status == 503 ? "RETRY_LATER" : "NONE");
    }

    private static void profile(ChatData.Profile profile, BiConsumer<String, String> header) {
        if (profile == null) return;
        if (SupportedLocale.valid(profile.locale())) header.accept(LOCALE_HEADER, profile.locale());
        if (profile.displayName() != null)
            header.accept(DISPLAY_NAME_HEADER, UriUtils.encode(profile.displayName(), StandardCharsets.UTF_8));
    }

    private static ChatCallFailure decodingFailure(JacksonException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause())
            if (cause instanceof IOException) return ChatCallFailure.unavailable();
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
                if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType))
                    throw ChatCallFailure.invalid();
                return ChatValidation.validate(JSON.readValue(response.getBody(), type));
            });
        } catch (ResourceAccessException failure) {
            throw ChatCallFailure.unavailable();
        } catch (JacksonException failure) {
            throw decodingFailure(failure);
        } catch (RestClientException | IllegalArgumentException | NullPointerException failure) {
            throw ChatCallFailure.invalid();
        }
    }

    public ChatData.Availability availability(UUID userId, UUID projectId) {
        return call(HttpMethod.GET, "/projects/{projectId}/chat/availability", Map.of("projectId", projectId), userId, null, null, ChatData.Availability.class, 200);
    }
    public ChatData.Sessions sessions(UUID userId, UUID projectId) {
        return call(HttpMethod.GET, "/projects/{projectId}/chat/sessions", Map.of("projectId", projectId), userId, null, null, ChatData.Sessions.class, 200);
    }
    public ChatData.Session createSession(UUID userId, UUID projectId, ChatData.Profile profile) {
        return call(HttpMethod.POST, "/projects/{projectId}/chat/sessions", Map.of("projectId", projectId), userId, profile, null, ChatData.Session.class, 201);
    }
    public ChatData.SearchHits search(UUID userId, UUID projectId, String query) {
        return call(HttpMethod.GET, "/projects/{projectId}/chat/search?q={query}", Map.of("projectId", projectId, "query", query == null ? "" : query), userId, null, null, ChatData.SearchHits.class, 200);
    }
    public ChatData.Session renameSession(UUID userId, UUID sessionId, ChatData.Rename body) {
        return call(HttpMethod.PATCH, "/chat/sessions/{sessionId}", Map.of("sessionId", sessionId), userId, null, body, ChatData.Session.class, 200);
    }
    public void deleteSession(UUID userId, UUID sessionId) {
        call(HttpMethod.DELETE, "/chat/sessions/{sessionId}", Map.of("sessionId", sessionId), userId, null, null, Void.class, 204);
    }
    public ChatData.Usage usage(UUID userId) {
        return call(HttpMethod.GET, "/chat/usage", Map.of(), userId, null, null, ChatData.Usage.class, 200);
    }
    public ChatData.Messages messages(UUID userId, UUID sessionId) {
        return call(HttpMethod.GET, "/chat/sessions/{sessionId}/messages", Map.of("sessionId", sessionId), userId, null, null, ChatData.Messages.class, 200);
    }
    public void cancel(UUID userId, UUID sessionId) {
        call(HttpMethod.POST, "/chat/sessions/{sessionId}/cancel", Map.of("sessionId", sessionId), userId, null, null, Void.class, 204);
    }
    public void deleteUserData(UUID userId) {
        call(HttpMethod.DELETE, "/users/me/data", Map.of(), userId, null, null, Void.class, 204);
    }
    public void deleteProjectData(UUID userId, UUID projectId) {
        call(HttpMethod.DELETE, "/projects/{projectId}/chat/data", Map.of("projectId", projectId), userId, null, null, Void.class, 204);
    }

    public ChatStream send(UUID userId, UUID sessionId, ChatData.SendInput body, ChatData.Profile profile) {
        HttpPost request;
        try {
            request = new HttpPost(stream.uri("/chat/sessions/{sessionId}/messages", Map.of("sessionId", Objects.requireNonNull(sessionId))));
            request.setHeader("X-User-Id", Objects.requireNonNull(userId).toString());
            request.setHeader("Accept", "text/event-stream, application/json");
            profile(profile, request::setHeader);
            request.setEntity(new ByteArrayEntity(JSON.writeValueAsBytes(body), ContentType.APPLICATION_JSON));
        } catch (JacksonException | IllegalArgumentException | NullPointerException failure) {
            throw ChatCallFailure.invalid();
        }
        ClassicHttpResponse response;
        try {
            response = stream.http().executeOpen(null, request, null);
        } catch (IOException failure) {
            UpstreamChatStream.abort(request, null);
            throw ChatCallFailure.unavailable();
        }
        boolean streaming = false;
        try {
            var entity = response.getEntity();
            var contentType = entity == null || entity.getContentType() == null ? null : MediaType.parseMediaType(entity.getContentType());
            int status = response.getCode();
            if (status == 200) {
                if (contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) throw ChatCallFailure.invalid();
                var opened = new UpstreamChatStream(request, response, entity.getContent());
                streaming = true;
                return opened;
            }
            throw failure(status, contentType, entity == null ? InputStream.nullInputStream() : entity.getContent());
        } catch (IOException failure) {
            throw ChatCallFailure.unavailable();
        } catch (JacksonException failure) {
            throw decodingFailure(failure);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw ChatCallFailure.invalid();
        } finally {
            if (!streaming) UpstreamChatStream.abort(request, response);
        }
    }
}
