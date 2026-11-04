package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.logging.LogFlow;

final class UpstreamChatStream implements ChatStream {
    private final HttpUriRequestBase request;
    private final ClassicHttpResponse response;
    private final InputStream body;
    /** 이 스트림의 ai-chat 대화 세션. relay의 모든 줄에 붙는다. */
    private final UUID sessionId;
    private final AtomicBoolean finished = new AtomicBoolean();

    UpstreamChatStream(HttpUriRequestBase request, ClassicHttpResponse response, InputStream body, UUID sessionId) {
        this.request = request; this.response = response; this.body = body; this.sessionId = sessionId;
    }

    /**
     * ai-chat SSE 본문을 브라우저로 그대로 넘긴다. MVC 비동기 실행기 스레드에서 돈다(AppLogger는 ContextPropagatingTaskDecorator가 옮겨 온다).
     * relay 하나가 작업 흐름 하나다: 시작 줄 "ai-chat stream relay start"와 종료 줄 하나를 남기고, 모든 줄에 chat.session_id가 붙는다.
     * <ul>
     *   <li>정상 종료: "ai-chat stream relay completed"(complete=true, 넘긴 바이트 수)</li>
     *   <li>ai-chat 쪽 끊김: 원인 줄 "ai-chat stream ended before completion"과 종료 줄 "ai-chat stream relay failed"
     *       (error.type AI_CHAT_STREAM_INTERRUPTED). 같은 이유를 요청 결과 줄(request completed)에도 맡긴다</li>
     *   <li>브라우저 쪽 끊김(탭 닫기·연결 끊김): 실패가 아니라 취소라 "ai-chat stream relay completed"(browser_disconnected=true)이고,
     *       그 예외는 종료 줄을 남긴 뒤 그대로 다시 던진다</li>
     *   <li>그 밖의 예외: 종료 줄 "ai-chat stream relay failed"(error, 메시지 없는 스택)를 남기고 그대로 던진다</li>
     * </ul>
     * 본문 내용은 남기지 않는다.
     */
    @Override
    public void relay(OutputStream browser) throws IOException {
        // ai-chat이 답을 끝까지 보냈는지. 끝까지 받았으면 연결을 돌려주고(release), 아니면 끊는다(close)
        var complete = new AtomicBoolean();
        // 브라우저 쪽 쓰기 실패. relay 종료 줄을 남긴 뒤 밖으로 다시 던진다(MVC가 비동기 요청을 끝낸다)
        var disconnected = new AtomicReference<IOException>();
        try {
            LogFlow.task("ai-chat stream relay", relay -> {
                relay.with(LogField.chatSessionId(sessionId));
                // 브라우저로 넘긴 바이트 수. 종료 줄의 결과 값이다
                long relayed = 0;
                try {
                    browser.flush();
                    var buffer = new byte[8192];
                    while (true) {
                        int read;
                        try {
                            read = body.read(buffer);
                        } catch (IOException failure) {
                            // ai-chat 쪽 연결이 답을 다 보내기 전에 끊겼다. 원인(예외 종류·스택)은 이 줄, 이유는 종료 줄과
                            // 요청 결과 줄(request completed)에 남긴다
                            AppLogger.current().warn("ai-chat stream ended before completion", LogField.errorType(failure.getClass().getName()),
                                    LogField.safeStackTrace(failure));
                            AppLogger.current().addToOutcome(LogField.errorType("AI_CHAT_STREAM_INTERRUPTED"));
                            relay.fail("AI_CHAT_STREAM_INTERRUPTED");
                            break;
                        }
                        if (read < 0) {
                            complete.set(true);
                            break;
                        }
                        browser.write(buffer, 0, read);
                        browser.flush();
                        relayed += read;
                    }
                } catch (IOException failure) {
                    // ai-chat 읽기 실패는 위에서 처리하므로 여기 오는 것은 브라우저 쪽 쓰기 실패다(탭 닫기·연결 끊김)
                    disconnected.set(failure);
                    relay.result(LogField.result("browser_disconnected", true));
                }
                relay.result(LogField.result("complete", complete.get()), LogField.result("bytes", relayed));
            });
        } finally {
            if (complete.get()) release(); else close();
        }
        if (disconnected.get() != null) throw disconnected.get();
    }

    @Override
    public void close() {
        if (finished.compareAndSet(false, true)) abort(request, response);
    }

    private void release() {
        if (!finished.compareAndSet(false, true)) return;
        try {
            response.close();
        } catch (IOException ignored) {
            abort(request, response);
        }
    }

    static void abort(HttpUriRequestBase request, ClassicHttpResponse response) {
        request.cancel();
        if (response == null) return;
        if (response instanceof ModalCloseable closeable) {
            closeable.close(CloseMode.IMMEDIATE);
            return;
        }
        try {
            response.close();
        } catch (IOException ignored) {
        }
    }
}
