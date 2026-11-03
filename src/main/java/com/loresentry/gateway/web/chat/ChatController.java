package com.loresentry.gateway.web.chat;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.loresentry.gateway.application.ChatService;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 브라우저의 AI 채팅 요청을 ai-chat으로 넘기는 컨트롤러.
 *
 * <p>각 핸들러의 첫 줄은 진입 로그다. 프로젝트·대화 세션 UUID만 남기고, 질문·인용 본문, 대화 제목, 검색어 q는 남기지 않는다.
 */
@RestController
public class ChatController {
    static final MediaType EVENT_STREAM = new MediaType("text", "event-stream", StandardCharsets.UTF_8);
    private final ChatService service;
    public ChatController(ChatService service) { this.service = service; }

    @GetMapping("/projects/{projectId}/chat/availability")
    public ResponseEntity<ChatDtos.Availability> availability(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("chat availability get requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Availability.from(service.availability(userId, projectId)));
    }
    @GetMapping("/projects/{projectId}/chat/sessions")
    public ResponseEntity<ChatDtos.Sessions> sessions(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("chat session list requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Sessions.from(service.sessions(userId, projectId)));
    }
    @PostMapping("/projects/{projectId}/chat/sessions")
    public ResponseEntity<ChatDtos.Session> createSession(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("chat session create requested", LogField.projectId(projectId));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ChatDtos.Session.from(service.createSession(userId, projectId)));
    }
    @GetMapping("/projects/{projectId}/chat/search")
    public ResponseEntity<ChatDtos.SearchHits> search(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestParam(name = "q", required = false) String query) {
        AppLogger.current().info("chat search requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.SearchHits.from(service.search(userId, projectId, query)));
    }
    @PatchMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<ChatDtos.Session> renameSession(@CurrentUser UUID userId, @PathVariable UUID sessionId, @RequestBody ChatDtos.Rename body) {
        AppLogger.current().info("chat session rename requested", LogField.chatSessionId(sessionId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Session.from(service.renameSession(userId, sessionId, body == null ? null : body.internal())));
    }
    @DeleteMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@CurrentUser UUID userId, @PathVariable UUID sessionId) {
        AppLogger.current().info("chat session delete requested", LogField.chatSessionId(sessionId));
        service.deleteSession(userId, sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/chat/usage")
    public ResponseEntity<ChatDtos.Usage> usage(@CurrentUser UUID userId) {
        AppLogger.current().info("chat usage get requested");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Usage.from(service.usage(userId)));
    }
    @GetMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<ChatDtos.Messages> messages(@CurrentUser UUID userId, @PathVariable UUID sessionId) {
        AppLogger.current().info("chat message list requested", LogField.chatSessionId(sessionId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Messages.from(service.messages(userId, sessionId)));
    }
    @PostMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<StreamingResponseBody> send(@CurrentUser UUID userId, @PathVariable UUID sessionId, @RequestBody ChatDtos.SendInput body, HttpServletResponse response) {
        AppLogger.current().info("chat message send requested", LogField.chatSessionId(sessionId));
        var stream = service.send(userId, sessionId, body == null ? null : body.internal());
        return ResponseEntity.ok().contentType(EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(output -> stream.relay(flushing(output, response)));
    }
    @PostMapping("/chat/sessions/{sessionId}/cancel")
    public ResponseEntity<Void> cancel(@CurrentUser UUID userId, @PathVariable UUID sessionId) {
        AppLogger.current().info("chat answer cancel requested", LogField.chatSessionId(sessionId));
        service.cancel(userId, sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static OutputStream flushing(OutputStream body, HttpServletResponse response) {
        return new FilterOutputStream(body) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
            @Override public void flush() throws IOException { response.flushBuffer(); }
        };
    }
}
