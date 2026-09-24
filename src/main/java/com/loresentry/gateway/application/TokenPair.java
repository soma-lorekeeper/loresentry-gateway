package com.loresentry.gateway.application;

import java.time.Instant;

/** Request-scoped application result, never an external response body. */
public record TokenPair(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {
    @Override public String toString() { return "TokenPair[redacted]"; }
}
