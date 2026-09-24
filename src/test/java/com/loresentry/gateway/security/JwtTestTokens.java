package com.loresentry.gateway.security;

import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import tools.jackson.databind.json.JsonMapper;

public final class JwtTestTokens {
    public static final Instant NOW=Instant.parse("2026-09-24T00:00:00Z");
    public static final UUID USER=UUID.fromString("0195a860-0000-7000-8000-000000000001");
    public static final UUID SID=UUID.fromString("8ab8809c-ff40-48c3-9010-bbf4a609017c");
    public static final KeyPair KEYS=keys(2048);
    public static KeyPair keys(int bits) {
        try { var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(bits);return generator.generateKeyPair(); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static AccessTokenVerifier verifier() { return new AccessTokenVerifier((RSAPublicKey)KEYS.getPublic(),"key",Clock.fixed(NOW,ZoneOffset.UTC)); }
    public static String token(Consumer<Map<String,Object>> change) { return token(change,"key",JWSAlgorithm.RS256,KEYS); }
    public static String token(Consumer<Map<String,Object>> change,String kid,JWSAlgorithm algorithm,KeyPair keys) {
        try {
            Map<String,Object> claims=new LinkedHashMap<>();
            claims.put("iss","loresentry-auth");claims.put("aud",List.of("loresentry-api"));
            claims.put("sub",USER.toString());claims.put("sid",SID.toString());claims.put("jti",UUID.randomUUID().toString());
            claims.put("iat",NOW.getEpochSecond());claims.put("exp",NOW.plusSeconds(900).getEpochSecond());claims.put("token_type","access");
            change.accept(claims);
            var jwt=new JWSObject(new JWSHeader.Builder(algorithm).keyID(kid).build(),new Payload(JsonMapper.builder().build().writeValueAsString(claims)));
            jwt.sign(new RSASSASigner((RSAPrivateKey)keys.getPrivate()));return jwt.serialize();
        } catch(Exception e){throw new IllegalStateException(e);}
    }
}
