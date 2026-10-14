package com.loresentry.gateway.client.chat;

import java.io.IOException;
import java.io.OutputStream;

public interface ChatStream extends AutoCloseable {
    void relay(OutputStream browser) throws IOException;

    @Override
    void close();
}
