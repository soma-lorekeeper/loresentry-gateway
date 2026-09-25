package com.loresentry.gateway.security;

public class SecurityFailure extends RuntimeException {
    public enum Reason {
        CSRF_REJECTED(403,"NONE","Request origin verification failed."),
        SESSION_REQUIRED(401,"RELOGIN","A login session is required."),
        SESSION_INVALID(401,"RELOGIN","The login session is no longer active."),
        SESSION_UNAVAILABLE(503,"RETRY_LATER","The login session could not be verified.");
        public final int status;
        public final String nextAction;
        public final String message;
        Reason(int status,String nextAction,String message) { this.status=status;this.nextAction=nextAction;this.message=message; }
    }
    private final Reason reason;
    public SecurityFailure(Reason reason) { super(reason.name());this.reason=reason; }
    public Reason reason() { return reason; }
}
