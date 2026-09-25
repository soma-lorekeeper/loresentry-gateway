package com.loresentry.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.loresentry.gateway.application.SessionId;
import com.loresentry.gateway.client.session.OpaqueSessionReader;
import com.loresentry.gateway.client.session.SessionLookupFailure;
import org.junit.jupiter.api.Test;

class OpaqueSessionVerifierTest {
    @Test void malformedOrMissingCredentialsNeverReachRedis() {
        var reader=mock(OpaqueSessionReader.class);var verifier=new OpaqueSessionVerifier(reader);
        assertThatThrownBy(()->verifier.verify(null)).hasMessage("SESSION_REQUIRED");
        for(String raw:java.util.List.of("", "a".repeat(64), "A".repeat(42)+"B", "A".repeat(43)+"="))
            assertThatThrownBy(()->verifier.verify(raw)).hasMessage("SESSION_INVALID");
        verifyNoInteractions(reader);
    }
    @Test void unknownOutcomesFailClosedWithoutReplaying() {
        var reader=mock(OpaqueSessionReader.class);var verifier=new OpaqueSessionVerifier(reader);
        var id=new SessionId("A".repeat(43));
        when(reader.verifyAndExtend(id)).thenThrow(new SessionLookupFailure(SessionLookupFailure.Kind.UNAVAILABLE));
        assertThatThrownBy(()->verifier.verify(id.value())).hasMessage("SESSION_UNAVAILABLE");
        verify(reader,times(1)).verifyAndExtend(id);
    }
}
