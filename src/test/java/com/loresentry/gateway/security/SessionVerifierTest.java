package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.loresentry.gateway.client.session.SessionReader;
import com.loresentry.gateway.client.session.SessionReadFailure;

class SessionVerifierTest {
    static final AccessTokenVerifier.Claims CLAIMS=new AccessTokenVerifier.Claims(JwtTestTokens.USER,JwtTestTokens.SID,UUID.randomUUID(),JwtTestTokens.NOW,JwtTestTokens.NOW.plusSeconds(900));
    @ParameterizedTest @ValueSource(strings={"null","{}","{", "[]",
        "{\"schema_version\":2}", "{\"schema_version\":\"1\"}", "{\"schema_version\":1,\"sid\":false}",
        "{\"schema_version\":1,\"schema_version\":1}"})
    void corruptRecordIsUnavailable(String raw) {
        var reader=mock(SessionReader.class);when(reader.get(CLAIMS.userId())).thenReturn(raw);
        assertThatThrownBy(()->new SessionVerifier(reader,Clock.fixed(JwtTestTokens.NOW,ZoneOffset.UTC)).verify(CLAIMS)).hasMessage("SESSION_UNAVAILABLE");
        verify(reader,times(1)).get(CLAIMS.userId());
    }
    @Test void missingAndTransportFailureDiffer() {
        var reader=mock(SessionReader.class);var verifier=new SessionVerifier(reader,Clock.systemUTC());
        assertThatThrownBy(()->verifier.verify(CLAIMS)).hasMessage("SESSION_INVALID");
        when(reader.get(CLAIMS.userId())).thenThrow(new SessionReadFailure());
        assertThatThrownBy(()->verifier.verify(CLAIMS)).hasMessage("SESSION_UNAVAILABLE");
    }
}
