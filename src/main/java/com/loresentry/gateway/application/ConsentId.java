package com.loresentry.gateway.application;

/** An opaque consent credential, never a login session. */
public record ConsentId(String value) {
    public ConsentId { new SessionId(value); }
    @Override public String toString() { return "ConsentId[redacted]"; }
}
