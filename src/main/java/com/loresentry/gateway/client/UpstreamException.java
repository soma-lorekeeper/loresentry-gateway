package com.loresentry.gateway.client;

public class UpstreamException extends RuntimeException {

    private final String upstream;

    public UpstreamException(String upstream, String message, Throwable cause) {
        super(message, cause);
        this.upstream = upstream;
    }

    public String getUpstream() {
        return upstream;
    }
}
