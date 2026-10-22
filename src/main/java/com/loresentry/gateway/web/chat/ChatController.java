package com.loresentry.gateway.web.chat;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.loresentry.gateway.application.ChatService;
import com.loresentry.gateway.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class ChatController {
    static final MediaType EVENT_STREAM = new MediaType("text", "event-stream", StandardCharsets.UTF_8);
    private final ChatService service;
    public ChatController(ChatService service) { this.service = service; }

    @GetMapping("/projects/{projectId}/chat/availability")
    public ResponseEntity<ChatDtos.Availability> availability(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Availability.from(service.availability(userId, projectId)));
    }
    @GetMapping("/projects/{projectId}/chat/sessions")
    public ResponseEntity<ChatDtos.Sessions> sessions(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Sessions.from(service.sessions(userId, projectId)));
    }
    @PostMapping("/projects/{projectId}/chat/sessions")
    public ResponseEntity<ChatDtos.Session> createSession(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ChatDtos.Session.from(service.createSession(userId, projectId)));
    }
    @GetMapping("/projects/{projectId}/chat/search")
    public ResponseEntity<ChatDtos.SearchHits> search(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestParam(name = "q", required = false) String query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.SearchHits.from(service.search(userId, projectId, query)));
    }
    @PatchMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<ChatDtos.Session> renameSession(@CurrentUser UUID userId, @PathVariable UUID sessionId, @RequestBody ChatDtos.Rename body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Session.from(service.renameSession(userId, sessionId, body == null ? null : body.internal())));
    }
    @DeleteMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@CurrentUser UUID userId, @PathVariable UUID sessionId) {
        service.deleteSession(userId, sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/chat/usage")
    public ResponseEntity<ChatDtos.Usage> usage(@CurrentUser UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Usage.from(service.usage(userId)));
    }
    @GetMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<ChatDtos.Messages> messages(@CurrentUser UUID userId, @PathVariable UUID sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ChatDtos.Messages.from(service.messages(userId, sessionId)));
    }
    @PostMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<StreamingResponseBody> send(@CurrentUser UUID userId, @PathVariable UUID sessionId, @RequestBody ChatDtos.SendInput body, HttpServletResponse response) {
        var stream = service.send(userId, sessionId, body == null ? null : body.internal());
        return ResponseEntity.ok().contentType(EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(output -> stream.relay(flushing(output, response)));
    }
    @PostMapping("/chat/sessions/{sessionId}/cancel")
    public ResponseEntity<Void> cancel(@CurrentUser UUID userId, @PathVariable UUID sessionId) {
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
