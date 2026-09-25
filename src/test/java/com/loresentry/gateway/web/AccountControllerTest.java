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
@Import({CurrentUserArgumentResolver.class,JsonConfiguration.class})
class AccountControllerTest {
    @Autowired MockMvc mvc;@MockitoBean AccountService service;
    UUID user=UUID.randomUUID();
    @Test void getAndPatchMapExplicitAccountDto() throws Exception {
        when(service.get(user)).thenReturn(new AccountService.Account(user,"Name",null));
        when(service.update(user,"New name")).thenReturn(new AccountService.Account(user,"New name","user@example.test"));
        mvc.perform(get("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.toString())).andExpect(jsonPath("$.display_name").value("Name"))
            .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue())).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(patch("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user).contentType(MediaType.APPLICATION_JSON).content("{\"display_name\":\"New name\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.display_name").value("New name"));
        verify(service).update(user,"New name");
    }
    @ParameterizedTest @ValueSource(strings={"{\"display_name\":42}","{\"display_name\":\"Name\",\"id\":\"other\"}","{\"display_name\":\"Name\",\"email\":\"other@example.test\"}"})
    void unknownFieldsAndCoercionAreRejectedBeforeAuth(String body) throws Exception {
        mvc.perform(patch("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));verifyNoInteractions(service);
    }
    @Test void unavailableDoesNotModifyCookiesOrRequestRefresh() throws Exception {
        when(service.get(user)).thenThrow(new AuthOperationFailure(503,"ACCOUNT_UNAVAILABLE","RETRY_LATER"));
        mvc.perform(get("/auth/users/me").requestAttr(SessionFilter.USER_ATTRIBUTE,user))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.next_action").value("RETRY_LATER"))
            .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control","no-store"));
    }
}
