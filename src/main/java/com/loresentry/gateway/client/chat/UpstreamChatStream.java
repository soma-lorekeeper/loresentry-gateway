package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;

final class UpstreamChatStream implements ChatStream {
    private final HttpUriRequestBase request;
    private final ClassicHttpResponse response;
    private final InputStream body;
    private final AtomicBoolean finished = new AtomicBoolean();

    UpstreamChatStream(HttpUriRequestBase request, ClassicHttpResponse response, InputStream body) {
        this.request = request; this.response = response; this.body = body;
    }

    /**
     * ai-chat SSE 본문을 브라우저로 그대로 넘긴다. MVC 비동기 실행기 스레드에서 돈다(AppLogger는 ContextPropagatingTaskDecorator가 옮겨 온다).
     * 끝나면 결과 줄 하나를 남긴다: 정상 종료·ai-chat 쪽 끊김은 "ai-chat stream relay completed"(complete, 넘긴 바이트 수),
     * 브라우저 쪽 끊김은 "ai-chat stream cancelled: browser disconnected"(그 예외는 그대로 다시 던진다). 본문 내용은 남기지 않는다.
     */
    @Override
    public void relay(OutputStream browser) throws IOException {
        boolean complete = false;
        // 브라우저로 넘긴 바이트 수. 결과 줄에만 쓴다
        long relayed = 0;
        try {
            try {
                browser.flush();
                var buffer = new byte[8192];
                while (true) {
                    int read;
                    try {
                        read = body.read(buffer);
                    } catch (IOException failure) {
                        // ai-chat 쪽 연결이 답을 다 보내기 전에 끊겼다. 원인은 여기, 이유는 결과 줄(request completed)에 맡긴다
                        AppLogger.current().warn("ai-chat stream ended before completion", LogField.errorType(failure.getClass().getName()));
                        AppLogger.current().addToOutcome(LogField.errorType("AI_CHAT_STREAM_INTERRUPTED"));
                        break;
                    }
                    if (read < 0) {
                        complete = true;
                        break;
                    }
                    browser.write(buffer, 0, read);
                    browser.flush();
                    relayed += read;
                }
            } catch (IOException failure) {
                // ai-chat 읽기 실패는 위에서 처리하므로 여기 오는 것은 브라우저 쪽 쓰기 실패다(탭 닫기·연결 끊김)
                AppLogger.current().info("ai-chat stream cancelled: browser disconnected", LogField.errorType(failure.getClass().getName()),
                        LogField.result("bytes", relayed));
                throw failure;
            }
            AppLogger.current().info("ai-chat stream relay completed", LogField.result("complete", complete), LogField.result("bytes", relayed));
        } finally {
            if (complete) release(); else close();
        }
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
