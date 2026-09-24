package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static com.loresentry.gateway.security.JwtTestTokens.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.nimbusds.jose.JWSAlgorithm;

class AccessTokenVerifierTest {
    void invalid(String token) {assertThatThrownBy(()->verifier().verify(token)).isInstanceOf(SecurityFailure.class).hasMessage("ACCESS_TOKEN_INVALID");}
    @Test void acceptsAuthenticAccessTokenAndAudienceArray() {
        var claims=verifier().verify(token(c->{}));assertThat(claims.userId()).isEqualTo(USER);assertThat(claims.sessionId()).isEqualTo(SID);
        assertThat(verifier().verify(token(c->c.put("aud",java.util.List.of("other","loresentry-api"))))).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"iss","aud","sub","sid","jti","iat","exp","token_type"})
    void rejectsMissingClaims(String field) {invalid(token(c->c.remove(field)));}
    @ParameterizedTest @ValueSource(strings={"iss","aud","sub","sid","jti","token_type"})
    void rejectsWrongClaimTypes(String field) {invalid(token(c->c.put(field,42)));}
    @Test void rejectsWrongKeyAlgorithmKidAudienceAndTokenType() {
        invalid(token(c->{},"key",JWSAlgorithm.RS256,keys(2048)));
        invalid(token(c->{},"other",JWSAlgorithm.RS256,KEYS));
        invalid(token(c->{},null,JWSAlgorithm.RS256,KEYS));
        invalid(token(c->{},"key",JWSAlgorithm.RS512,KEYS));
        invalid("eyJhbGciOiJub25lIn0.e30.");
        invalid(token(c->c.put("iss","other")));invalid(token(c->c.put("aud","loresentry-auth")));
        invalid(token(c->c.put("token_type","refresh")));invalid(token(c->c.put("sid",USER.toString())));
        invalid(token(c->c.put("sub","1-1-1-1-1")));
    }
    @Test void enforcesTimeBoundariesAndDistinguishesExpiredOnlyAfterOtherChecks() {
        assertThat(verifier().verify(token(c->{c.put("iat",NOW.plusSeconds(30).getEpochSecond());}))).isNotNull();
        invalid(token(c->c.put("iat",NOW.plusSeconds(31).getEpochSecond())));
        invalid(token(c->c.put("exp",NOW.getEpochSecond())));
        invalid(token(c->c.put("iat",NOW.getEpochSecond()+0.5)));
        invalid(token(c->c.put("exp","1790208900")));
        assertThat(verifier().verify(token(c->{c.put("iat",NOW.minusSeconds(100).getEpochSecond());c.put("exp",NOW.minusSeconds(29).getEpochSecond());}))).isNotNull();
        String expired=token(c->{c.put("iat",NOW.minusSeconds(100).getEpochSecond());c.put("exp",NOW.minusSeconds(30).getEpochSecond());});
        assertThatThrownBy(()->verifier().verify(expired)).hasMessage("ACCESS_TOKEN_EXPIRED");
        invalid(token(c->{c.put("iat",NOW.minusSeconds(100).getEpochSecond());c.put("exp",NOW.minusSeconds(30).getEpochSecond());c.remove("sid");}));
        assertThatThrownBy(()->verifier().verify(null)).hasMessage("ACCESS_TOKEN_MISSING");invalid("");
    }
}
