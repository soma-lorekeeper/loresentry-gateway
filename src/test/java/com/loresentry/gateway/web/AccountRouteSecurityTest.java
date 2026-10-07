package com.loresentry.gateway.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.loresentry.gateway.application.AccountDeletionService;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.client.session.OpaqueSessionReader.VerifiedSession;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.security.*;
import com.loresentry.gateway.web.auth.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(AccountController.class)
@Import({CurrentUserArgumentResolver.class,JsonConfiguration.class,AccountRouteSecurityTest.Wiring.class})
class AccountRouteSecurityTest {
    static final Instant NOW=Instant.parse("2026-10-07T00:00:00Z");
    static final String ID="A".repeat(43);
    static final UUID USER=UUID.randomUUID();
    @Autowired MockMvc mvc;
    @MockitoBean AccountService service;@MockitoBean AccountDeletionService deletions;@MockitoBean OpaqueSessionVerifier verifier;
    @TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean CookieSettings names(){return new CookieSettings("ls_oauth",false);}
        @Bean AuthCookies cookies(CookieSettings names,Clock clock){return new AuthCookies(names,clock);}
        @Bean @Order(1) CsrfFilter csrf(){var cors=new CorsConfiguration();cors.setAllowedOrigins(List.of("http://localhost:3000"));return new CsrfFilter(cors);}
        @Bean @Order(2) SessionFilter session(OpaqueSessionVerifier verifier,CookieSettings names,AuthCookies cookies){return new SessionFilter(verifier,names,cookies);}
    }
    @BeforeEach void setup() {
        when(verifier.verify(ID)).thenReturn(new VerifiedSession(USER,NOW.plusSeconds(1209600)));
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
        var account=new AccountService.Account(USER,"Name",null,true,"en");
        when(service.update(USER,"Name")).thenReturn(account);when(service.updateLocale(USER,"en")).thenReturn(account);
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={"PATCH|/auth/users/me|{\"display_name\":\"Name\"}","PUT|/auth/users/me/locale|{\"locale\":\"en\"}"})
    void csrfPrecedesTheSessionLikeEveryAccountWrite(String method,String path,String body) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).cookie(new Cookie("ls_session",ID)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(request(HttpMethod.valueOf(method),path).cookie(new Cookie("ls_session",ID)).header("Origin","http://localhost:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED")).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(verifier,service);
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={"PATCH|/auth/users/me|{\"display_name\":\"Name\"}","PUT|/auth/users/me/locale|{\"locale\":\"en\"}"})
    void missingSessionNeverReachesAuth(String method,String path,String body) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).header("Origin","http://localhost:3000").header("X-LS-CSRF","1")
                .header("X-User-Id",USER).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REQUIRED")).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(service);
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={"PATCH|/auth/users/me|{\"display_name\":\"Name\"}","PUT|/auth/users/me/locale|{\"locale\":\"en\"}"})
    void verifiedSessionIsRenewedAndOnlyItsUserIsForwarded(String method,String path,String body) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).cookie(new Cookie("ls_session",ID)).header("Origin","http://localhost:3000").header("X-LS-CSRF","1")
                .header("X-User-Id",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(USER.toString())).andExpect(jsonPath("$.locale").value("en"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.startsWith("ls_session="+ID)));
        verify(verifier).verify(ID);
        if("PUT".equals(method)) verify(service).updateLocale(USER,"en"); else verify(service).update(USER,"Name");
    }
}
