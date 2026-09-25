package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.web.auth.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionCookiesTest {
    static final Instant NOW=Instant.parse("2026-09-26T00:00:00.500Z");
    static final String ID="A".repeat(43);
    AuthCookies cookies(boolean secure,Clock clock) {
        return new AuthCookies(new CookieSettings("oauth",secure),clock);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void singleCookieAndDeletionHaveIdenticalScope(boolean secure) {
        var service=cookies(secure,Clock.fixed(NOW,ZoneOffset.UTC));
        var issued=service.session(ID,NOW.plusSeconds(1209600));var deleted=service.clearSession();
        assertThat(issued.getName()).isEqualTo(secure?"__Host-ls_session":"ls_session");
        assertThat(issued.getValue()).isEqualTo(ID);assertThat(issued.isHttpOnly()).isTrue();
        assertThat(issued.isSecure()).isEqualTo(secure);assertThat(issued.getSameSite()).isEqualTo("Strict");
        assertThat(issued.getPath()).isEqualTo("/");assertThat(issued.getDomain()).isNull();
        assertThat(issued.getMaxAge()).isEqualTo(Duration.ofDays(14));
        assertThat(deleted.getName()).isEqualTo(issued.getName());assertThat(deleted.getMaxAge()).isZero();
        assertThat(deleted.getDomain()).isNull();assertThat(deleted.getPath()).isEqualTo("/");
    }
    @Test void remainingLifetimeIsFlooredAndCappedWithoutSkewGrace() {
        var service=cookies(true,Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(service.session(ID,NOW.plusMillis(1999)).getMaxAge()).isEqualTo(Duration.ofSeconds(1));
        assertThat(service.session(ID,NOW.plusSeconds(9999999)).getMaxAge()).isEqualTo(Duration.ofDays(14));
        for(var expiry:java.util.List.of(NOW,NOW.minusSeconds(1),NOW.plusMillis(999)))
            assertThatThrownBy(()->service.session(ID,expiry)).isInstanceOf(IllegalArgumentException.class);
        var response=new MockHttpServletResponse();
        assertThatThrownBy(()->service.setSession(response,"bad",NOW.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }
    @Test void domainFailureRenewsAtHeaderWriteTimeAndCannotEnableCaching() throws Exception {
        var clock=mock(Clock.class);when(clock.instant()).thenReturn(NOW);
        var response=new MockHttpServletResponse();
        var wrapped=new SessionCookieResponse(response,cookies(true,clock),ID,NOW.plusSeconds(1209600));
        when(clock.instant()).thenReturn(NOW.plusMillis(10999));
        wrapped.setStatus(409);wrapped.setHeader("Cache-Control","public,max-age=30");
        wrapped.getOutputStream().write("domain conflict".getBytes());wrapped.finish();
        assertThat(response.getStatus()).isEqualTo(409);assertThat(response.getContentAsString()).isEqualTo("domain conflict");
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=1209589").contains(ID);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void emptyAndResetResponsesStillRenewOnlyOneCookie() throws Exception {
        var response=new MockHttpServletResponse();
        var wrapped=new SessionCookieResponse(response,cookies(false,Clock.fixed(NOW,ZoneOffset.UTC)),ID,NOW.plusSeconds(100));
        wrapped.finish();wrapped.reset();wrapped.setStatus(204);wrapped.finish();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
        assertThat(response.getContentAsString()).isEmpty();assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void invalidExpiryNeverChangesExistingCookies() {
        var response=new MockHttpServletResponse();response.addHeader("Set-Cookie","unrelated=keep");
        assertThatThrownBy(()->new SessionCookieResponse(response,cookies(false,Clock.fixed(NOW,ZoneOffset.UTC)),ID,NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(response.getHeaders("Set-Cookie")).containsExactly("unrelated=keep");
    }
}
