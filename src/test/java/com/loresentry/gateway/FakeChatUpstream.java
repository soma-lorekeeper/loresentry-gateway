package com.loresentry.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FakeChatUpstream implements AutoCloseable {
    public record Request(String method, String path, Map<String, String> headers, String body) {
        public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    @FunctionalInterface
    public interface Script {
        void run(Exchange exchange) throws Exception;
    }

    public static final class Exchange {
        private final Socket socket;
        private final OutputStream out;

        private Exchange(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        public void respond(int status, String contentType, String body) throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            write("HTTP/1.1 " + status + " X\r\nContent-Type: " + contentType + "\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n");
            out.write(bytes);
            out.flush();
        }

        public void startEvents() throws IOException {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n");
        }

        public void chunk(String data) throws IOException {
            var bytes = data.getBytes(StandardCharsets.UTF_8);
            write(Integer.toHexString(bytes.length) + "\r\n");
            out.write(bytes);
            write("\r\n");
        }

        public void end() throws IOException {
            write("0\r\n\r\n");
        }

        public boolean closedByPeerWithin(Duration limit) throws IOException {
            socket.setSoTimeout((int) limit.toMillis());
            try {
                return socket.getInputStream().read() < 0;
            } catch (SocketTimeoutException stillOpen) {
                return false;
            } catch (IOException reset) {
                return true;
            }
        }

        private void write(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    public static final class Call {
        private final Script script;
        private final CompletableFuture<Request> request = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();

        private Call(Script script) { this.script = script; }

        public Request request() throws Exception { return request.get(10, TimeUnit.SECONDS); }

        public void awaitScript() throws Exception { finished.get(30, TimeUnit.SECONDS); }

        private void serve(Socket socket) {
            try (socket) {
                request.complete(read(socket.getInputStream()));
                script.run(new Exchange(socket));
                finished.complete(null);
            } catch (Throwable failure) {
                request.completeExceptionally(failure);
                finished.completeExceptionally(failure);
            }
        }
    }

    private final ServerSocket server;
    private final LinkedBlockingQueue<Call> calls = new LinkedBlockingQueue<>();
    private Call single;

    private FakeChatUpstream() throws IOException {
        server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    var socket = server.accept();
                    var call = calls.poll(5, TimeUnit.SECONDS);
                    if (call == null) socket.close();
                    else Thread.ofVirtual().start(() -> call.serve(socket));
                } catch (IOException | InterruptedException stopped) {
                    return;
                }
            }
        });
    }

    public static FakeChatUpstream listen() throws IOException {
        return new FakeChatUpstream();
    }

    public static FakeChatUpstream start(Script script) throws IOException {
        var upstream = new FakeChatUpstream();
        upstream.single = upstream.expect(script);
        return upstream;
    }

    public Call expect(Script script) {
        var call = new Call(script);
        calls.add(call);
        return call;
    }

    public int port() { return server.getLocalPort(); }

    public String baseUrl() { return "http://127.0.0.1:" + port(); }

    public Request request() throws Exception { return single.request(); }

    public void awaitScript() throws Exception { single.awaitScript(); }

    @Override
    public void close() throws IOException { server.close(); }

    private static Request read(InputStream input) throws IOException {
        var head = new ByteArrayOutputStream();
        int current, newlines = 0;
        while ((current = input.read()) >= 0) {
            head.write(current);
            if (current == '\n') { if (++newlines == 2) break; }
            else if (current != '\r') newlines = 0;
        }
        var lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        var start = lines[0].split(" ");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        var body = input.readNBytes(length);
        return new Request(start[0], start[1], headers, new String(body, StandardCharsets.UTF_8));
    }
}
