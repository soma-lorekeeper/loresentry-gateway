package com.loresentry.gateway.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentCallFailure;
import com.loresentry.gateway.client.content.ContentData;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.security.CurrentUserArgumentResolver;
import com.loresentry.gateway.security.SessionFilter;
import com.loresentry.gateway.web.content.ContentApiController;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(ContentApiController.class)
@Import({ CurrentUserArgumentResolver.class, com.loresentry.gateway.config.JsonConfiguration.class })
@EnableConfigurationProperties(BrowserProperties.class)
class FeedbackRouteTest {

    private static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");

    private static final UUID ID = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d70");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContentService service;

    private MockHttpServletRequestBuilder send(String payload) {
        return post("/feedback").requestAttr(SessionFilter.USER_ATTRIBUTE, USER)
                .contentType(MediaType.APPLICATION_JSON).content(payload);
    }

    @Test
    void relaysTheCreatedFeedback() throws Exception {
        var input = new ContentData.FeedbackInput("BUG", "저장이 안 돼요", "/projects/", "Mozilla/5.0");
        given(service.createFeedback(eq(USER), eq(input), any()))
                .willReturn(new ContentData.FeedbackCreated(ID, OffsetDateTime.parse("2026-10-02T00:00:00Z")));

        mvc.perform(send("{\"category\":\"BUG\",\"message\":\"저장이 안 돼요\",\"page\":\"/projects/\",\"client\":\"Mozilla/5.0\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.created_at").value("2026-10-02T00:00:00Z"))
                .andExpect(jsonPath("$.length()").value(2));

        verify(service).createFeedback(eq(USER), eq(input), any());
    }

    @Test
    void forwardsMissingPageAndClientAsNull() throws Exception {
        var input = new ContentData.FeedbackInput("IDEA", "다크 모드", null, null);
        given(service.createFeedback(eq(USER), eq(input), any()))
                .willReturn(new ContentData.FeedbackCreated(ID, OffsetDateTime.parse("2026-10-02T00:00:00Z")));

        mvc.perform(send("{\"category\":\"IDEA\",\"message\":\"다크 모드\",\"page\":null}"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"category\":\"BUG\",\"message\":\"m\",\"user_id\":\"x\"}", "{\"category\":\"BUG\",",
            "{\"category\":\"BUG\",\"message\":1}", "{\"category\":\"BUG\",\"message\":\"m\"} {}" })
    void rejectsNonContractJsonBeforeCallingContent(String payload) throws Exception {
        mvc.perform(send(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void passesInvalidFeedbackThrough() throws Exception {
        given(service.createFeedback(eq(USER), any(), any()))
                .willThrow(new ContentCallFailure(400, "INVALID_FEEDBACK", null, null));

        mvc.perform(send("{\"category\":\"BUG\",\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_FEEDBACK"))
                .andExpect(jsonPath("$.next_action").value("NONE"));
    }

    @Test
    void passesTheRateLimitThroughWithRetryLater() throws Exception {
        given(service.createFeedback(eq(USER), any(), any()))
                .willThrow(new ContentCallFailure(429, "FEEDBACK_RATE_LIMITED", null, null));

        mvc.perform(send("{\"category\":\"OTHER\",\"message\":\"m\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("FEEDBACK_RATE_LIMITED"))
                .andExpect(jsonPath("$.next_action").value("RETRY_LATER"));
    }

    @Test
    void requiresTheSessionUser() throws Exception {
        mvc.perform(post("/feedback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BUG\",\"message\":\"m\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void onlyAcceptsPost() throws Exception {
        mvc.perform(get("/feedback").requestAttr(SessionFilter.USER_ATTRIBUTE, USER))
                .andExpect(status().isMethodNotAllowed());
    }
}
