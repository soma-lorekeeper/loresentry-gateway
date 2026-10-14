package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UpstreamChatStream implements ChatStream {
    private static final Logger log = LoggerFactory.getLogger(UpstreamChatStream.class);
    private final HttpUriRequestBase request;
    private final ClassicHttpResponse response;
    private final InputStream body;
    private final AtomicBoolean finished = new AtomicBoolean();

    UpstreamChatStream(HttpUriRequestBase request, ClassicHttpResponse response, InputStream body) {
        this.request = request; this.response = response; this.body = body;
    }

    @Override
    public void relay(OutputStream browser) throws IOException {
        boolean complete = false;
        try {
            browser.flush();
            var buffer = new byte[8192];
            while (true) {
                int read;
                try {
                    read = body.read(buffer);
                } catch (IOException failure) {
                    log.warn("ai-chat stream ended before completion: {}", failure.getClass().getSimpleName());
                    return;
                }
                if (read < 0) {
                    complete = true;
                    return;
                }
                browser.write(buffer, 0, read);
                browser.flush();
            }
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
