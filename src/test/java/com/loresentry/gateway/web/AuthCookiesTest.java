package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.web.auth.AuthCookies;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCookiesTest {
    static final Instant NOW=Instant.parse("2026-09-24T00:00:00.500Z");
    AuthCookies cookies(boolean secure) {
        String prefix=secure?"__Host-":"";
        return new AuthCookies(new CookieSettings(prefix+"ls_oauth",secure),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void oauthKeepsItsTemporaryLaxScope(boolean secure) {
        var service=cookies(secure);var issued=service.oauth("request-id",NOW.plusSeconds(1000));
        assertThat(issued.getMaxAge()).isEqualTo(Duration.ofSeconds(300));
        assertThat(issued.getSameSite()).isEqualTo("Lax");
        assertThat(issued.isSecure()).isEqualTo(secure);assertThat(issued.isHttpOnly()).isTrue();
        assertThat(issued.getPath()).isEqualTo("/");assertThat(issued.getDomain()).isNull();
        assertThat(service.clearOAuth().getName()).isEqualTo(issued.getName());
        assertThat(service.clearOAuth().getMaxAge()).isZero();
        assertThatThrownBy(()->service.oauth("bad;cookie",NOW.plusSeconds(20))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void migrationDeletesOnlyPresentLegacyCookiesForTheCurrentEnvironment(boolean secure) {
        String prefix=secure?"__Host-":"";
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("ls_at","old"),new jakarta.servlet.http.Cookie("ls_rt","old"),
            new jakarta.servlet.http.Cookie("__Host-ls_at","old"),new jakarta.servlet.http.Cookie("__Host-ls_rt","old"),
            new jakarta.servlet.http.Cookie(prefix+"ls_session","new"));
        var deleted=cookies(secure).clearLegacy(request);
        assertThat(deleted).extracting(org.springframework.http.ResponseCookie::getName).containsExactly(prefix+"ls_at",prefix+"ls_rt");
        assertThat(deleted).allSatisfy(cookie->{assertThat(cookie.getMaxAge()).isZero();assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.getDomain()).isNull();assertThat(cookie.isSecure()).isEqualTo(secure);});
        assertThat(cookies(secure).clearLegacy(new org.springframework.mock.web.MockHttpServletRequest())).isEmpty();
    }
}
