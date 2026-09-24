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
import com.loresentry.gateway.config.CorsProperties;
import com.loresentry.gateway.identity.ClientHeaderIdentityResolver;
import com.loresentry.gateway.identity.CurrentUserArgumentResolver;
import com.loresentry.gateway.web.content.ContentApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ContentApiController.class)
@Import({ClientHeaderIdentityResolver.class, CurrentUserArgumentResolver.class})
@EnableConfigurationProperties(CorsProperties.class)
class ContentApiRoutesTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID ID = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    @Autowired MockMvc mvc;
    @MockitoBean ContentService service;

    @Test void mapsProjectCreationToPublicDtoAndRelativeLocation() throws Exception {
        given(service.createProject(eq(USER), any(), any())).willReturn(new ContentData.Project(
            ID, "Novel", "", OffsetDateTime.parse("2026-09-24T00:00:00Z"), null,
            OffsetDateTime.parse("2026-09-24T00:00:00Z"), null));
        mvc.perform(post("/projects").header("X-User-Id", USER)
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
        mvc.perform(put("/files/"+ID+"/content").header("X-User-Id",USER)
                .header("If-Match","\"7\"").header("X-Save-Id",ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Title\",\"body_md\":\"body\",\"properties\":[],\"relations\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision_no").value(8))
            .andExpect(jsonPath("$.episode_id").value(org.hamcrest.Matchers.nullValue()));
        verify(service).saveContent(eq(USER),eq(ID),any(),eq(new Conditions("\"7\"",ID.toString(),null)));
    }
    @Test void rejectsAmbiguousHeadersBeforeCallingApplication() throws Exception {
        mvc.perform(put("/files/"+ID+"/content").header("X-User-Id",USER).header("If-Match","1","2")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void doesNotExposeArbitraryNamespacePathsOrMethods() throws Exception {
        mvc.perform(get("/projects/"+ID+"/internal").header("X-User-Id",USER)).andExpect(status().isNotFound());
        mvc.perform(put("/projects/"+ID).header("X-User-Id",USER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
    @Test void deleteHasNoResponseBodyAndUsesExplicitUser() throws Exception {
        mvc.perform(delete("/episodes/"+ID).header("X-User-Id",USER)).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).deleteEpisode(USER,ID,new Conditions(null,null,null));
    }
    @Test void searchPassesDecodedQueryMeaningAndConditionalHeader() throws Exception {
        given(service.search(eq(USER),eq(ID),eq("유리 & +?"),any())).willReturn(new ContentData.Hits(List.of()));
        mvc.perform(get("/projects/"+ID+"/search").param("q","유리 & +?")
                .header("X-User-Id",USER).header("If-None-Match","\"etag\""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hits").isEmpty());
        verify(service).search(USER,ID,"유리 & +?",new Conditions(null,null,"\"etag\""));
    }
}
