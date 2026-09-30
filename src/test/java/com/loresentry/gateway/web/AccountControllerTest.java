package com.loresentry.gateway.web;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.application.AuthOperationFailure;
import com.loresentry.gateway.security.SessionFilter;
import com.loresentry.gateway.security.CurrentUserArgumentResolver;
import com.loresentry.gateway.web.auth.AccountController;
import com.loresentry.gateway.config.JsonConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(AccountController.class)
@Import({CurrentUserArgumentResolver.class,JsonConfiguration.class,AccountControllerTest.Wiring.class})
class AccountControllerTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        @org.springframework.context.annotation.Bean com.loresentry.gateway.web.auth.AuthCookies cookies() {
            return new com.loresentry.gateway.web.auth.AuthCookies(new com.loresentry.gateway.config.CookieSettings("ls_oauth",false),java.time.Clock.systemUTC());
        }
    }
    @Autowired MockMvc mvc;@MockitoBean AccountService service;@MockitoBean com.loresentry.gateway.application.AccountDeletionService deletions;
    UUID user=UUID.randomUUID();
    @Test void getAndPatchMapExplicitAccountDto() throws Exception {
        when(service.get(user)).thenReturn(new AccountService.Account(user,"Name",null,false));
        when(service.update(user,"New name")).thenReturn(new AccountService.Account(user,"New name","user@example.test",true));
        mvc.perform(get("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.toString())).andExpect(jsonPath("$.display_name").value("Name"))
            .andExpect(jsonPath("$.onboarding_completed").value(false)).andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue())).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(patch("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user).contentType(MediaType.APPLICATION_JSON).content("{\"display_name\":\"New name\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.display_name").value("New name")).andExpect(jsonPath("$.onboarding_completed").value(true));
        verify(service).update(user,"New name");
    }
    @ParameterizedTest @ValueSource(strings={"{\"display_name\":42}","{\"display_name\":\"Name\",\"id\":\"other\"}","{\"display_name\":\"Name\",\"email\":\"other@example.test\"}"})
    void unknownFieldsAndCoercionAreRejectedBeforeAuth(String body) throws Exception {
        mvc.perform(patch("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));verifyNoInteractions(service);
    }
    @Test void onboardingCompletionIsBodilessAndNotCached() throws Exception {
        mvc.perform(put("/auth/users/me/onboarding").requestAttr(SessionFilter.USER_ATTRIBUTE,user))
            .andExpect(status().isNoContent()).andExpect(content().string("")).andExpect(header().string("Cache-Control","no-store"));
        verify(service).completeOnboarding(user);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"401,USER_CONTEXT_REQUIRED,RELOGIN","404,USER_NOT_FOUND,RELOGIN",
        "503,ACCOUNT_UNAVAILABLE,RETRY_LATER","502,UPSTREAM_INVALID_RESPONSE,NONE"})
    void onboardingFailuresUseTheAccountErrorBody(int status,String code,String action) throws Exception {
        doThrow(new AuthOperationFailure(status,code,action)).when(service).completeOnboarding(user);
        mvc.perform(put("/auth/users/me/onboarding").requestAttr(SessionFilter.USER_ATTRIBUTE,user))
            .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.next_action").value(action))
            .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void unavailableDoesNotModifyCookiesOrRequestRefresh() throws Exception {
        when(service.get(user)).thenThrow(new AuthOperationFailure(503,"ACCOUNT_UNAVAILABLE","RETRY_LATER"));
        mvc.perform(get("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.next_action").value("RETRY_LATER"))
            .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control","no-store"));
    }
}
