package com.loresentry.gateway.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentData;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.security.CurrentUserArgumentResolver;
import com.loresentry.gateway.web.content.ContentApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(ContentApiController.class)
@Import({CurrentUserArgumentResolver.class, com.loresentry.gateway.config.JsonConfiguration.class})
@EnableConfigurationProperties(BrowserProperties.class)
class ContentApiRoutesTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID ID = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    @Autowired MockMvc mvc;
    @MockitoBean ContentService service;

    @Test void mapsProjectCreationToPublicDtoAndRelativeLocation() throws Exception {
        given(service.createProject(eq(USER), any(), any())).willReturn(new ContentData.Project(
            ID, "Novel", "", OffsetDateTime.parse("2026-09-24T00:00:00Z"), null,
            OffsetDateTime.parse("2026-09-24T00:00:00Z"), null));
        mvc.perform(post("/projects").requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Novel\",\"description\":\"\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("Location", "/projects/" + ID))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.name").value("Novel")).andExpect(jsonPath("$.last_file").value(org.hamcrest.Matchers.nullValue()));
        verify(service).createProject(USER, new ContentData.ProjectInput("Novel", ""), new Conditions(null,null,null));
    }
    @Test void keepsSaveRevisionAndIdempotencyHeaders() throws Exception {
        var now = OffsetDateTime.parse("2026-09-24T00:00:00Z");
        given(service.saveContent(eq(USER), eq(ID), any(), any())).willReturn(new ContentData.Content(
            ID, ID, "Title", "MANUSCRIPT", null, "body", List.of(), List.of(), false, 4, 8L, now));
        mvc.perform(put("/files/"+ID+"/content").requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER)
                .header("If-Match","\"7\"").header("X-Save-Id",ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Title\",\"body_md\":\"body\",\"properties\":[],\"relations\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision_no").value(8))
            .andExpect(jsonPath("$.episode_id").value(org.hamcrest.Matchers.nullValue()));
        verify(service).saveContent(eq(USER),eq(ID),any(),eq(new Conditions("\"7\"",ID.toString(),null)));
    }
    @Test void rejectsAmbiguousHeadersBeforeCallingApplication() throws Exception {
        mvc.perform(put("/files/"+ID+"/content").requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER).header("If-Match","1","2")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void doesNotExposeArbitraryNamespacePathsOrMethods() throws Exception {
        mvc.perform(get("/projects/"+ID+"/internal").requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER)).andExpect(status().isNotFound());
        mvc.perform(put("/projects/"+ID).requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
    @Test void deleteHasNoResponseBodyAndUsesExplicitUser() throws Exception {
        mvc.perform(delete("/episodes/"+ID).requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER)).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).deleteEpisode(USER,ID,new Conditions(null,null,null));
    }
    @Test void searchPassesDecodedQueryMeaningAndConditionalHeader() throws Exception {
        given(service.search(eq(USER),eq(ID),eq("유리 & +?"),any())).willReturn(new ContentData.Hits(List.of()));
        mvc.perform(get("/projects/"+ID+"/search").param("q","유리 & +?")
                .requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER).header("If-None-Match","\"etag\""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hits").isEmpty());
        verify(service).search(USER,ID,"유리 & +?",new Conditions(null,null,"\"etag\""));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{\"name\":42}","{\"name\":true}","{\"name\":\"ok\",\"admin\":true}","{} {}","{"})
    void rejectsInvalidInputWithoutInternalCall(String body) throws Exception {
        mvc.perform(post("/projects").requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.next_action").value("NONE"));
        verifyNoInteractions(service);
    }
    @Test void invalidPathPreservesNotFoundContract() throws Exception {
        mvc.perform(get("/files/not-a-uuid/content").requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        verifyNoInteractions(service);
    }
    @Test void mapsSafeBusinessErrorAndPreservesConflictSnapshot() throws Exception {
        var now = OffsetDateTime.parse("2026-09-24T00:00:00Z");
        var current = new ContentData.Content(ID,ID,"Latest","MANUSCRIPT",null,"body",List.of(),List.of(),false,4,9L,now);
        given(service.saveContent(any(),any(),any(),any())).willThrow(
            new com.loresentry.gateway.client.content.ContentCallFailure(409,"DOCUMENT_CONFLICT",current,null));
        mvc.perform(put("/files/"+ID+"/content").requestAttr(com.loresentry.gateway.security.SessionFilter.USER_ATTRIBUTE, USER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_CONFLICT"))
            .andExpect(jsonPath("$.current.revision_no").value(9)).andExpect(jsonPath("$.base").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(header().string("Cache-Control","no-store"));
    }
}
