package com.loresentry.gateway.client.session;
public class SessionReadFailure extends RuntimeException {
    public SessionReadFailure() { super("Session lookup unavailable"); }
}
