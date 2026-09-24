package com.loresentry.gateway.security;

import java.time.Clock;
import java.util.UUID;
import com.loresentry.gateway.client.session.SessionReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

public class SessionVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SessionReader reader;
    private final Clock clock;
    public SessionVerifier(SessionReader reader,Clock clock) { this.reader=reader;this.clock=clock; }
    public void verify(AccessTokenVerifier.Claims claims) {
        String raw;
        UUID sid;
        long expiry;
        try {
            raw=reader.get(claims.userId());
            if(raw==null) throw new SecurityFailure(SecurityFailure.Reason.SESSION_INVALID);
            if(raw.length()>16384) throw new IllegalArgumentException();
            var record=JSON.readTree(raw);
            if(!record.isObject()||integer(record.get("schema_version"))!=1) throw new IllegalArgumentException();
            sid=uuid4(record.get("sid"));uuid4(record.get("refresh_jti"));
            expiry=integer(record.get("refresh_expires_at"));
            java.time.Instant.ofEpochSecond(expiry);
        } catch(SecurityFailure failure) { throw failure; }
        catch(Exception unavailable) { throw new SecurityFailure(SecurityFailure.Reason.SESSION_UNAVAILABLE); }
        if(expiry<=clock.instant().getEpochSecond()||!sid.equals(claims.sessionId()))
            throw new SecurityFailure(SecurityFailure.Reason.SESSION_INVALID);
    }
    private static long integer(JsonNode value) {
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()) throw new IllegalArgumentException();
        return value.longValue();
    }
    private static UUID uuid4(JsonNode value) {
        if(value==null||!value.isString()) throw new IllegalArgumentException();
        var uuid=AccessTokenVerifier.uuid(value.stringValue());
        if(uuid.version()!=4||uuid.variant()!=2) throw new IllegalArgumentException();return uuid;
    }
}
