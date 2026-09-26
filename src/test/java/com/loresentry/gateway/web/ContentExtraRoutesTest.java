package com.loresentry.gateway.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentData;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.security.AccessTokenFilter;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메모·즐겨찾기·작업공간 상태·이미지. Content 가 이 경로를 추가한 뒤에도 BFF 가 명시 선언하지
 * 않으면 404 가 되므로, 노출 목록에 들어 있다는 것 자체를 테스트로 고정한다.
 */
@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(ContentApiController.class)
@Import({ CurrentUserArgumentResolver.class, com.loresentry.gateway.config.JsonConfiguration.class })
@EnableConfigurationProperties(BrowserProperties.class)
class ContentExtraRoutesTest {

    private static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");

    private static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");

    private static final UUID ID = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");

    private static final OffsetDateTime WHEN = OffsetDateTime.parse("2026-09-26T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContentService service;

    private final JsonMapper json = JsonMapper.builder().build();

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.requestAttr(AccessTokenFilter.USER_ATTRIBUTE, USER);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String payload) {
        return as(request).contentType(MediaType.APPLICATION_JSON).content(payload);
    }

    private static ContentData.Memo memo(String scope, UUID documentId, String body) {
        return new ContentData.Memo(ID, PROJECT, scope, documentId, null, body, WHEN, WHEN);
    }

    @Test
    void listsProjectMemos() throws Exception {
        given(service.listMemos(eq(USER), eq(PROJECT), eq("project"), eq(null), any()))
                .willReturn(new ContentData.Memos(List.of(memo("project", null, "작품 메모"))));

        mvc.perform(as(get("/projects/" + PROJECT + "/memos?scope=project")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.memos[0].body").value("작품 메모"))
                .andExpect(jsonPath("$.memos[0].document_id").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void carriesTheDocumentThroughToFileMemos() throws Exception {
        given(service.listMemos(eq(USER), eq(PROJECT), eq("file"), eq(ID), any()))
                .willReturn(new ContentData.Memos(List.of(memo("file", ID, "파일 메모"))));

        mvc.perform(as(get("/projects/" + PROJECT + "/memos?scope=file&document_id=" + ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memos[0].scope").value("file"));

        verify(service).listMemos(eq(USER), eq(PROJECT), eq("file"), eq(ID), any());
    }

    @Test
    void createsAMemoWithARelativeLocation() throws Exception {
        given(service.createMemo(eq(USER), eq(PROJECT), any(), any()))
                .willReturn(memo("project", null, "새 메모"));

        mvc.perform(body(post("/projects/" + PROJECT + "/memos"),
                        "{\"scope\":\"project\",\"body\":\"새 메모\"}"))
                .andExpect(status().isCreated())
                // 내부 Location 원문을 복사하지 않고 검증된 id 로 구성한다.
                .andExpect(header().string("Location", "/memos/" + ID));
    }

    @Test
    void updatesAndDeletesAMemo() throws Exception {
        given(service.updateMemo(eq(USER), eq(ID), any(), any()))
                .willReturn(memo("project", null, "고친 메모"));

        mvc.perform(body(patch("/memos/" + ID), "{\"body\":\"고친 메모\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("고친 메모"));

        mvc.perform(as(delete("/memos/" + ID))).andExpect(status().isNoContent());
        verify(service).deleteMemo(eq(USER), eq(ID), any());
    }

    @Test
    void returnsTheWholeFavoriteListOnEveryChange() throws Exception {
        given(service.listFavorites(eq(USER), eq(PROJECT), any()))
                .willReturn(new ContentData.Favorites(List.of(ID)));
        given(service.addFavorite(eq(USER), eq(PROJECT), eq(ID), any()))
                .willReturn(new ContentData.Favorites(List.of(ID)));
        given(service.removeFavorite(eq(USER), eq(PROJECT), eq(ID), any()))
                .willReturn(new ContentData.Favorites(List.of()));

        mvc.perform(as(get("/projects/" + PROJECT + "/favorites")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file_ids[0]").value(ID.toString()));
        mvc.perform(as(put("/projects/" + PROJECT + "/favorites/" + ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file_ids.length()").value(1));
        mvc.perform(as(delete("/projects/" + PROJECT + "/favorites/" + ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file_ids.length()").value(0));
    }

    @Test
    void passesTheLayoutThroughWithoutInterpretingIt() throws Exception {
        var layout = json.readTree("{\"panes\":[{\"activeTabId\":\"t1\"}],\"sidebarOpen\":true}");
        given(service.loadWorkspaceState(eq(USER), eq(PROJECT), any()))
                .willReturn(new ContentData.WorkspaceState(layout));

        mvc.perform(as(get("/projects/" + PROJECT + "/workspace-state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layout.sidebarOpen").value(true))
                .andExpect(jsonPath("$.layout.panes[0].activeTabId").value("t1"));

        mvc.perform(body(put("/projects/" + PROJECT + "/workspace-state"),
                        "{\"layout\":{\"sidebarOpen\":false}}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void reportsNoLayoutForAFirstVisitWithoutTurningItIntoAnError() throws Exception {
        given(service.loadWorkspaceState(eq(USER), eq(PROJECT), any()))
                .willReturn(new ContentData.WorkspaceState(null));

        mvc.perform(as(get("/projects/" + PROJECT + "/workspace-state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layout").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void handsOutAnUploadTicketAndCommitsIt() throws Exception {
        given(service.createImageTicket(eq(USER), eq(PROJECT), any(), any()))
                .willReturn(new ContentData.ImageTicket(ID, "projects/p/images/a.png",
                        "https://s3.example/put", "PUT", Map.of("Content-Type", "image/png"),
                        Instant.parse("2026-09-26T00:05:00Z"), "https://media.loresentry.com/a.png"));
        given(service.completeImage(eq(USER), eq(PROJECT), eq(ID), any()))
                .willReturn(new ContentData.Image(ID, PROJECT, "a.png", "projects/p/images/a.png",
                        "image/png", 1234L, "COMMITTED", "https://media.loresentry.com/a.png",
                        WHEN, WHEN));

        mvc.perform(body(post("/projects/" + PROJECT + "/images"),
                        "{\"file_name\":\"a.png\",\"content_type\":\"image/png\",\"size_bytes\":1234}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/projects/" + PROJECT + "/images/" + ID))
                .andExpect(jsonPath("$.upload_url").value("https://s3.example/put"))
                .andExpect(jsonPath("$.headers['Content-Type']").value("image/png"));

        mvc.perform(as(post("/projects/" + PROJECT + "/images/" + ID + "/complete")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.public_url").value("https://media.loresentry.com/a.png"));
    }

    @Test
    void servesOneImage() throws Exception {
        given(service.getImage(eq(USER), eq(PROJECT), eq(ID), any()))
                .willReturn(new ContentData.Image(ID, PROJECT, null, "k", "image/png", 1L,
                        "PENDING", null, WHEN, null));

        mvc.perform(as(get("/projects/" + PROJECT + "/images/" + ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                // PENDING 의 주소를 주면 클라이언트가 문서에 넣고, 업로드 실패 시 깨진 이미지가 남는다.
                .andExpect(jsonPath("$.public_url").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void refusesMethodsItDoesNotDeclare() throws Exception {
        // 명시 선언이므로 선언하지 않은 메서드는 405 다.
        mvc.perform(as(post("/projects/" + PROJECT + "/workspace-state")))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(as(patch("/projects/" + PROJECT + "/favorites/" + ID)))
                .andExpect(status().isMethodNotAllowed());
    }
}
