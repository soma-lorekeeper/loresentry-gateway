package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.loresentry.gateway.application.TokenPair;
import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.web.auth.AuthCookies;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCookiesTest {
    static final Instant NOW=Instant.parse("2026-09-24T00:00:00.500Z");
    AuthCookies cookies(boolean secure) {
        String prefix=secure?"__Host-":"";
        return new AuthCookies(new CookieSettings(prefix+"ls_at",prefix+"ls_rt",prefix+"ls_oauth",secure),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    TokenPair pair() {return new TokenPair("access.payload.signature",NOW.plusSeconds(900),"refresh.payload.signature",NOW.plusSeconds(1209600));}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void setsCorrectScopeAndDeletesSameScope(boolean secure) {
        var service=cookies(secure);var issued=service.authentication(pair());var deleted=service.clearAuthentication();
        for(int i=0;i<2;i++) {
            var cookie=issued.get(i);assertThat(cookie.isHttpOnly()).isTrue();assertThat(cookie.isSecure()).isEqualTo(secure);
            assertThat(cookie.getSameSite()).isEqualTo("Strict");assertThat(cookie.getPath()).isEqualTo("/");assertThat(cookie.getDomain()).isNull();
            assertThat(cookie.getName()).startsWith(secure?"__Host-ls_":"ls_");
            assertThat(deleted.get(i).getName()).isEqualTo(cookie.getName());assertThat(deleted.get(i).getPath()).isEqualTo(cookie.getPath());
            assertThat(deleted.get(i).getMaxAge()).isZero();assertThat(deleted.get(i).getDomain()).isNull();
        }
        assertThat(service.oauth("request-id",NOW.plusSeconds(1000)).getMaxAge()).isEqualTo(Duration.ofSeconds(300));
        assertThat(service.oauth("request-id",NOW.plusSeconds(1000)).getSameSite()).isEqualTo("Lax");
        assertThat(service.clearOAuth().getName()).isEqualTo(service.oauth("request-id",NOW.plusSeconds(1000)).getName());
        assertThat(service.clearOAuth().getMaxAge()).isZero();
    }
    @Test void floorsRemainingTimeWithoutClockSkewAndCapsTtl() {
        var pair=new TokenPair("access.payload.signature",NOW.plusMillis(1999),"refresh.payload.signature",NOW.plusSeconds(9999999));
        var result=cookies(true).authentication(pair);
        assertThat(result.get(0).getMaxAge()).isEqualTo(Duration.ofSeconds(1));
        assertThat(result.get(1).getMaxAge()).isEqualTo(Duration.ofDays(14));
    }
    @Test void invalidSecondTokenNeverPartiallySetsFirst() {
        var response=new MockHttpServletResponse();
        assertThatThrownBy(()->cookies(true).setAuthentication(response,new TokenPair("access.payload.signature",NOW.plusSeconds(20),"bad token",NOW.plusSeconds(100))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }
    @Test void rejectsMissingExpiredOrSubsecondPairsAndDuplicateTokens() {
        var service=cookies(true);
        for(var expiry:java.util.List.of(NOW.minusSeconds(1),NOW,NOW.plusMillis(999)))
            assertThatThrownBy(()->service.authentication(new TokenPair("access.payload.signature",expiry,"refresh.payload.signature",NOW.plusSeconds(100))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.authentication(new TokenPair(null,NOW.plusSeconds(2),"refresh.payload.signature",NOW.plusSeconds(20)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.authentication(new TokenPair("access.payload.signature",null,"refresh.payload.signature",NOW.plusSeconds(20)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.authentication(new TokenPair("same.payload.signature",NOW.plusSeconds(2),"same.payload.signature",NOW.plusSeconds(20)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.oauth("bad;cookie",NOW.plusSeconds(20))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void secretsOnlyAppearInCookieHeadersAndToStringIsRedacted() throws Exception {
        var response=new MockHttpServletResponse();cookies(true).setAuthentication(response,pair());
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
        assertThat(response.getContentAsString()).isEmpty();assertThat(response.getHeader("Location")).isNull();
        assertThat(pair().toString()).doesNotContain("access.payload.signature","refresh.payload.signature");
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/auth/oauth/google/callback");
        new com.loresentry.gateway.web.auth.SensitiveResponseFilter().doFilter(request,response,(req,res)->{});
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
