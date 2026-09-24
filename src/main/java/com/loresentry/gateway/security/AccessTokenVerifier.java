package com.loresentry.gateway.security;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

public class AccessTokenVerifier {
    private final RSAPublicKey key;
    private final String keyId;
    private final Clock clock;
    public AccessTokenVerifier(RSAPublicKey key,String keyId,Clock clock) { this.key=key;this.keyId=keyId;this.clock=clock; }
    public record Claims(UUID userId,UUID sessionId,UUID tokenId,Instant issuedAt,Instant expiresAt) {
        @Override public String toString() { return "AccessClaims[redacted]"; }
    }
    public Claims verify(String raw) {
        if(raw==null) throw new SecurityFailure(SecurityFailure.Reason.ACCESS_TOKEN_MISSING);
        Claims verified;
        try {
            if(raw.length()>16384 || raw.isBlank()) throw new IllegalArgumentException();
            var jwt=SignedJWT.parse(raw);
            var header=jwt.getHeader();
            if(!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || !keyId.equals(header.getKeyID())
                    || (header.getCriticalParams()!=null&&!header.getCriticalParams().isEmpty())
                    || !jwt.verify(new RSASSAVerifier(key))) throw new IllegalArgumentException();
            var claims=jwt.getJWTClaimsSet();
            if(!"loresentry-auth".equals(claims.getIssuer()) || !claims.getAudience().contains("loresentry-api")
                    || !"access".equals(claims.getStringClaim("token_type"))) throw new IllegalArgumentException();
            var user=uuid(claims.getSubject());var id=uuid(claims.getJWTID());var sid=uuid(claims.getStringClaim("sid"));
            if(sid.version()!=4||sid.variant()!=2) throw new IllegalArgumentException();
            var payload=jwt.getPayload().toJSONObject();
            var issued=instant(payload.get("iat"));var expires=instant(payload.get("exp"));
            if(!expires.isAfter(issued)||issued.isAfter(clock.instant().plusSeconds(30))) throw new IllegalArgumentException();
            verified=new Claims(user,sid,id,issued,expires);
        } catch(Exception invalid) { throw new SecurityFailure(SecurityFailure.Reason.ACCESS_TOKEN_INVALID); }
        // Expiry is classified only after every other required check passes.
        if(!clock.instant().isBefore(verified.expiresAt().plusSeconds(30)))
            throw new SecurityFailure(SecurityFailure.Reason.ACCESS_TOKEN_EXPIRED);
        return verified;
    }
    private static Instant instant(Object value) {
        if(!(value instanceof Long || value instanceof Integer)) throw new IllegalArgumentException();
        return Instant.ofEpochSecond(((Number)value).longValue());
    }
    static UUID uuid(String value) {
        var uuid=UUID.fromString(value);
        if(!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return uuid;
    }
}
