package com.loresentry.gateway.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.client.content.ContentCallFailure;
import com.loresentry.gateway.client.content.ContentData;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.security.CurrentUserArgumentResolver;
import com.loresentry.gateway.security.SessionFilter;
import com.loresentry.gateway.web.content.ContentApiController;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 그래프 최신화 5개 경로. Content 와 같은 경로를 명시적으로 선언했는지, 응답·오류 상세를 브라우저 DTO 로
 * 옮기는지 고정한다.
 */
@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(ContentApiController.class)
@Import({ CurrentUserArgumentResolver.class, com.loresentry.gateway.config.JsonConfiguration.class })
@EnableConfigurationProperties(BrowserProperties.class)
class GraphRefreshRoutesTest {

    private static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");

    private static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");

    private static final UUID REQUEST = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");

    private static final UUID DRAFT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d63");

    private static final UUID FILE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64");

    private static final OffsetDateTime WHEN = OffsetDateTime.parse("2026-10-20T00:00:00Z");

    private static final String BASE = "/projects/" + PROJECT + "/graph-refresh";

    /** 본문은 에디터 문서 구조다. BFF 는 해석하지 않고 그대로 옮긴다. */
    private static final JsonNode BODY = JsonMapper.builder().build()
            .readTree("{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[]}}");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContentService service;

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.requestAttr(SessionFilter.USER_ATTRIBUTE, USER);
    }

    /** 관계 대상이 이번 최신화의 새 문서 제안인 작업본 한 쪽. */
    private static ContentData.DraftSnapshot side(String title) {
        return new ContentData.DraftSnapshot(title, BODY, null, List.of(new ContentData.TextProperty("description", "주인공")),
                List.of(new ContentData.DraftRelation("related_place", null, "new:PLACE:유리 정원", "")));
    }

    @Test
    void startAnswers202WhileGenerating() throws Exception {
        given(service.startRefresh(eq(USER), eq(PROJECT), any())).willReturn(new ContentData.RefreshStart(
                REQUEST, "GENERATING", List.of(new ContentData.RefreshFile(FILE, 3L))));

        mvc.perform(as(post(BASE)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.request_id").value(REQUEST.toString()))
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.files[0].file_id").value(FILE.toString()))
                .andExpect(jsonPath("$.files[0].revision_no").value(3));

        verify(service).startRefresh(USER, PROJECT, new Conditions(null, null, null));
    }

    @Test
    void startAnswers200WhenNothingChanged() throws Exception {
        given(service.startRefresh(eq(USER), eq(PROJECT), any()))
                .willReturn(new ContentData.RefreshStart(null, "NO_CHANGES", List.of()));

        mvc.perform(as(post(BASE)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                // null 필드도 생략하지 않는다.
                .andExpect(jsonPath("$.request_id").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.status").value("NO_CHANGES"))
                .andExpect(jsonPath("$.files").isEmpty());
    }

    @Test
    void summaryKeepsNullsAndRenamesNothing() throws Exception {
        given(service.latestRefresh(eq(USER), eq(PROJECT), any())).willReturn(new ContentData.RefreshSummary(
                REQUEST, "FAILED", WHEN, null, WHEN, new ContentData.RefreshError("TIMEOUT", null),
                new ContentData.DraftCounts(0L, 0L, 0L)));

        mvc.perform(as(get(BASE)).header("If-None-Match", "\"tag\""))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.ready_at").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.error.code").value("TIMEOUT"))
                .andExpect(jsonPath("$.error.message").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.draft_counts.new").value(0));

        verify(service).latestRefresh(USER, PROJECT, new Conditions(null, null, "\"tag\""));
    }

    @Test
    void detailCarriesEditAndNewDrafts() throws Exception {
        var edit = new ContentData.RefreshDraft(DRAFT, "EDIT", 1L, false, WHEN,
                new ContentData.RefreshDocument(FILE, "리아", "CHARACTER", 4L), null, FILE, 4L,
                new ContentData.Snapshot("리아", BODY, null, List.of(), List.of()), false, 4L, side("리아"), side("리아"));
        var created = new ContentData.RefreshDraft(REQUEST, "NEW", 0L, false, WHEN, null,
                new ContentData.RefreshCandidate("new:PLACE:유리 정원", "PLACE", "유리 정원"),
                null, null, null, false, null, null, null);
        given(service.getRefresh(eq(USER), eq(PROJECT), eq(REQUEST), any())).willReturn(new ContentData.RefreshDetail(
                REQUEST, "READY", WHEN, WHEN, null, null, new ContentData.DraftCounts(2L, 0L, 1L), List.of(edit, created)));

        mvc.perform(as(get(BASE + "/" + REQUEST)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.draft_counts.new").value(1))
                .andExpect(jsonPath("$.drafts[0].kind").value("EDIT"))
                .andExpect(jsonPath("$.drafts[0].document.revision_no").value(4))
                .andExpect(jsonPath("$.drafts[0].candidate").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.drafts[0].base_snapshot.body.schema_version").value(1))
                .andExpect(jsonPath("$.drafts[0].base_mismatch").value(false))
                .andExpect(jsonPath("$.drafts[0].right_snapshot.relations[0].target_candidate_id").value("new:PLACE:유리 정원"))
                .andExpect(jsonPath("$.drafts[0].right_snapshot.relations[0].target_document_id").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.drafts[1].candidate.name").value("유리 정원"))
                .andExpect(jsonPath("$.drafts[1].document").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.drafts[1].left_snapshot").value(Matchers.nullValue()));
    }

    @Test
    void savesADraftWithTheBodyTheBrowserSends() throws Exception {
        given(service.saveRefreshDraft(eq(USER), eq(PROJECT), eq(REQUEST), eq(DRAFT), any(), any()))
                .willReturn(new ContentData.SavedDraft(DRAFT, 4L, true, WHEN, side("리아"), side("리아")));

        mvc.perform(as(patch(BASE + "/" + REQUEST + "/drafts/" + DRAFT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft_revision\":3,\"left_snapshot\":null,\"right_snapshot\":{\"title\":\"리아\","
                                + "\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[]}},"
                                + "\"properties\":[{\"key\":\"description\",\"value\":\"주인공\"}],"
                                + "\"relations\":[{\"target_candidate_id\":\"new:PLACE:유리 정원\",\"description\":\"\"}]}}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.draft_revision").value(4))
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.left_snapshot.title").value("리아"));

        // 브라우저가 생략한 relation_key·legacy_body_md 는 null 로 전달한다. 관계 키는 Content 가 다시 정한다.
        var right = new ContentData.DraftSnapshot("리아", BODY, null, List.of(new ContentData.TextProperty("description", "주인공")),
                List.of(new ContentData.DraftRelation(null, null, "new:PLACE:유리 정원", "")));
        verify(service).saveRefreshDraft(USER, PROJECT, REQUEST, DRAFT, new ContentData.DraftSave(3L, null, right),
                new Conditions(null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"draft_revision\":3,\"left_snapshot\":null,\"right_snapshot\":null,\"matched\":true}",
            "{\"draft_revision\":\"3\",\"left_snapshot\":null,\"right_snapshot\":null}",
            "{\"draft_revision\":3,\"left_snapshot\":{\"title\":\"리아\",\"properties\":[],\"relations\":[],\"locked\":true},\"right_snapshot\":null}",
            "{\"draft_revision\":3,\"left_snapshot\":null,\"right_snapshot\":{\"title\":\"리아\",\"relations\":[{\"target_document_id\":\"x\"}]}}",
            "{\"draft_revision\":3,\"left_snapshot\":null,\"right_snapshot\":{\"title\":\"리아\",\"relations\":[{\"target_file_id\":null}]}}",
            "{\"draft_revision\":3} {}"})
    void rejectsUnknownOrMistypedDraftFieldsWithoutCallingContent(String payload) throws Exception {
        mvc.perform(as(patch(BASE + "/" + REQUEST + "/drafts/" + DRAFT)).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.next_action").value("NONE"));
        verifyNoInteractions(service);
    }

    @Test
    void confirmReportsWhatEachDraftBecame() throws Exception {
        given(service.confirmRefresh(eq(USER), eq(PROJECT), eq(REQUEST), any())).willReturn(new ContentData.RefreshConfirmed(
                REQUEST, "APPLIED", List.of(new ContentData.RefreshCreated(DRAFT, "new:PLACE:유리 정원", FILE)),
                List.of(new ContentData.RefreshUpdated(DRAFT, FILE, 5L)), List.of(DRAFT)));

        mvc.perform(as(post(BASE + "/" + REQUEST + "/confirm")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.created[0].candidate_id").value("new:PLACE:유리 정원"))
                .andExpect(jsonPath("$.updated[0].revision_no").value(5))
                .andExpect(jsonPath("$.unchanged[0]").value(DRAFT.toString()));
    }

    @Test
    void invalidRequestIdKeepsTheNotFoundContract() throws Exception {
        mvc.perform(as(get(BASE + "/not-a-uuid")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        verifyNoInteractions(service);
    }

    @Test
    void draftConflictCarriesTheCurrentDraft() throws Exception {
        given(service.saveRefreshDraft(any(), any(), any(), any(), any(), any())).willThrow(new ContentCallFailure(409,
                "REFRESH_DRAFT_CONFLICT", new ContentCallFailure.Details(
                        new ContentData.DraftConflict(5L, false, side("다른 탭의 리아"), null), null, null, null)));

        mvc.perform(as(patch(BASE + "/" + REQUEST + "/drafts/" + DRAFT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft_revision\":3,\"left_snapshot\":null,\"right_snapshot\":null}"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("REFRESH_DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.message").value("The operation conflicts with the current state."))
                .andExpect(jsonPath("$.next_action").value("NONE"))
                .andExpect(jsonPath("$.current.draft_revision").value(5))
                .andExpect(jsonPath("$.current.matched").value(false))
                .andExpect(jsonPath("$.current.left_snapshot.title").value("다른 탭의 리아"))
                .andExpect(jsonPath("$.current.right_snapshot").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.drafts").doesNotExist());
    }

    @Test
    void invalidDraftsCarryEachProblem() throws Exception {
        given(service.confirmRefresh(any(), any(), any(), any())).willThrow(new ContentCallFailure(409,
                "REFRESH_DRAFTS_INVALID", new ContentCallFailure.Details(null, null, null, List.of(
                        Map.of("code", "RELATION_CONFLICT", "one", FILE.toString(), "other", "new:PLACE:유리 정원"),
                        Map.of("code", "BODY_REQUIRED", "draft_id", DRAFT.toString())))));

        mvc.perform(as(post(BASE + "/" + REQUEST + "/confirm")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_DRAFTS_INVALID"))
                .andExpect(jsonPath("$.next_action").value("NONE"))
                .andExpect(jsonPath("$.problems.length()").value(2))
                .andExpect(jsonPath("$.problems[0].code").value("RELATION_CONFLICT"))
                .andExpect(jsonPath("$.problems[0].other").value("new:PLACE:유리 정원"))
                .andExpect(jsonPath("$.problems[1].draft_id").value(DRAFT.toString()))
                .andExpect(jsonPath("$.current").doesNotExist());
    }

    @Test
    void unmatchedAndChangedDraftsCarryTheirLists() throws Exception {
        given(service.confirmRefresh(any(), any(), any(), any()))
                .willThrow(new ContentCallFailure(409, "REFRESH_DRAFTS_UNMATCHED",
                        new ContentCallFailure.Details(null, List.of(DRAFT), null, null)))
                .willThrow(new ContentCallFailure(409, "REFRESH_DOCUMENTS_CHANGED",
                        new ContentCallFailure.Details(null, null, List.of(new ContentData.ChangedDocument(DRAFT, FILE)), null)));

        mvc.perform(as(post(BASE + "/" + REQUEST + "/confirm")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_DRAFTS_UNMATCHED"))
                .andExpect(jsonPath("$.drafts[0]").value(DRAFT.toString()));
        mvc.perform(as(post(BASE + "/" + REQUEST + "/confirm")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_DOCUMENTS_CHANGED"))
                .andExpect(jsonPath("$.documents[0].draft_id").value(DRAFT.toString()))
                .andExpect(jsonPath("$.documents[0].file_id").value(FILE.toString()));
    }

    /** 이미 진행 중인 최신화는 409 지만 잠시 뒤 다시 하면 된다. 나머지 최신화 오류는 상세 값 없이 세 칸이다. */
    @ParameterizedTest
    @CsvSource({ "409,REFRESH_IN_PROGRESS,RETRY_LATER", "503,REFRESH_UNAVAILABLE,RETRY_LATER",
            "409,REFRESH_REVIEW_PENDING,NONE", "404,REFRESH_NOT_FOUND,NONE", "409,REFRESH_NOT_READY,NONE" })
    void refreshErrorsChooseTheNextActionByCode(int status, String code, String action) throws Exception {
        given(service.startRefresh(any(), any(), any())).willThrow(new ContentCallFailure(status, code, null, null));

        mvc.perform(as(post(BASE)))
                .andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.next_action").value(action))
                .andExpect(jsonPath("$.current").doesNotExist());
    }

    /** 최신화가 잠근 문서는 기존 잠금 경로에서도 같은 409 로 전달된다. */
    @Test
    void refreshLockReachesTheLockRoute() throws Exception {
        given(service.setLocked(any(), any(), any(), any()))
                .willThrow(new ContentCallFailure(409, "DOCUMENT_REFRESH_LOCKED", null, null));

        mvc.perform(as(put("/files/" + FILE + "/lock")).contentType(MediaType.APPLICATION_JSON).content("{\"locked\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_REFRESH_LOCKED"))
                .andExpect(jsonPath("$.next_action").value("NONE"));
    }
}
