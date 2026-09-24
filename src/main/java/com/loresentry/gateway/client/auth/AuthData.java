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
    public record LoginTokens(@JsonProperty("access_token") String accessToken,
            @JsonProperty("access_expires_at") Instant accessExpiresAt,@JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_at") Instant refreshExpiresAt,@JsonProperty("login_request_consumed") Boolean consumed) {
        @Override public String toString() { return "LoginTokens[redacted]"; }
    }
    public record Refresh(@JsonProperty("refresh_token") String refreshToken) {
        @Override public String toString(){return "Refresh[redacted]";}
    }
    public record Tokens(@JsonProperty("access_token") String accessToken,
            @JsonProperty("access_expires_at") Instant accessExpiresAt,@JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_at") Instant refreshExpiresAt) {
        @Override public String toString(){return "Tokens[redacted]";}
    }
    public record Account(java.util.UUID id,@JsonProperty("display_name") String displayName,String email) {}
    public record DisplayName(@JsonProperty("display_name") String displayName) {}
    record Error(String code,String message,@JsonProperty("next_action") String nextAction,
                 @JsonProperty("login_request_consumed") Boolean consumed) {
        @Override public String toString() { return "AuthError[redacted]"; }
    }
}
