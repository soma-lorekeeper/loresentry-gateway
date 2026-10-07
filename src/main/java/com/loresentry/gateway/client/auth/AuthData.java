package com.loresentry.gateway.client.auth;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class AuthData {
    private AuthData() {}
    public record Empty() {}
    public record Prepared(@JsonProperty("authorization_url") String authorizationUrl,
            @JsonProperty("login_request_id") String loginRequestId,@JsonProperty("expires_at") Instant expiresAt) {
        @Override public String toString() { return "Prepared[redacted]"; }
    }
    public record Callback(@JsonProperty("login_request_id") String loginRequestId,String state,String code,String error) {
        @Override public String toString() { return "Callback[redacted]"; }
    }
    public record LoginSession(String status, @JsonProperty("session_id") String sessionId,
            @JsonProperty("consent_request_id") String consentRequestId,
            @JsonProperty("expires_at") Instant expiresAt,@JsonProperty("login_request_consumed") Boolean consumed) {
        @Override public String toString() { return "LoginSession[redacted]"; }
    }
    public record Session(@JsonProperty("session_id") String sessionId) {
        @Override public String toString(){return "Session[redacted]";}
    }
    public record Terms(@JsonProperty("terms_version_id") String termsVersionId, String version, String title, String content,
            @JsonProperty("effective_at") Instant effectiveAt, @JsonProperty("expires_at") Instant expiresAt, String locale) {}
    public record AcceptTerms(@JsonProperty("consent_request_id") String consentRequestId, @JsonProperty("terms_version_id") String termsVersionId) {
        @Override public String toString(){return "AcceptTerms[redacted]";}
    }
    public record AcceptedTerms(@JsonProperty("session_id") String sessionId, @JsonProperty("expires_at") Instant expiresAt) {
        @Override public String toString(){return "AcceptedTerms[redacted]";}
    }
    public record Account(java.util.UUID id,@JsonProperty("display_name") String displayName,String email,
            @JsonProperty("onboarding_completed") Boolean onboardingCompleted,String locale) {}
    public record DisplayName(@JsonProperty("display_name") String displayName) {}
    public record AccountLocale(String locale) {}
    record Error(String code,String message,@JsonProperty("next_action") String nextAction,
                 @JsonProperty("login_request_consumed") Boolean consumed) {
        @Override public String toString() { return "AuthError[redacted]"; }
    }
}
