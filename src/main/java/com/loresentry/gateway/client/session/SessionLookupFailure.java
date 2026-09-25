package com.loresentry.gateway.client.session;

public final class SessionLookupFailure extends RuntimeException {
    public enum Kind { INVALID, UNAVAILABLE }
    private final Kind kind;
    public SessionLookupFailure(Kind kind) { super(kind.name()); this.kind = kind; }
    public Kind kind() { return kind; }
}
