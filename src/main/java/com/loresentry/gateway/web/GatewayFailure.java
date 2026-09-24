package com.loresentry.gateway.web;

/** Request boundary failures, independent from internal service errors. */
public class GatewayFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        USER_CONTEXT_REQUIRED,
        UPSTREAM_UNAVAILABLE,
        INTERNAL_ERROR
    }

    private final Reason reason;

    public GatewayFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
