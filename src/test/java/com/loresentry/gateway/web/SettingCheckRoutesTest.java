package com.loresentry.gateway.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.loresentry.gateway.application.*;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.SettingCheckData;
import com.loresentry.gateway.client.session.OpaqueSessionReader.VerifiedSession;
import com.loresentry.gateway.config.*;
import com.loresentry.gateway.security.*;
import com.loresentry.gateway.web.auth.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.cors.CorsConfiguration;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest(com.loresentry.gateway.web.chat.SettingCheckController.class)
@Import({JsonConfiguration.class, CurrentUserArgumentResolver.class, SettingCheckService.class, SettingCheckRoutesTest.Wiring.class})
class SettingCheckRoutesTest {
    static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    static final String WRITER_SESSION = "A".repeat(43), OUTSIDER_SESSION = "B".repeat(42) + "A";
    static final UUID WRITER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID OUTSIDER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d69");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID RUN = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d70");
    static final UUID CHAPTER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d71");
    static final UUID FINDING = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d72");
    static final UUID PLACE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d73");
    static final UUID EARLIER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d74");
    static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-10-27T12:40:00Z"), FINISHED = OffsetDateTime.parse("2026-10-27T12:43:10Z");
    static final SettingCheckData.Finding CORE = new SettingCheckData.Finding(FINDING, "SETTING", true, "HIGH",
            "북쪽 문은 낡은 열쇠로만 열리는데, 서윤이 열쇠 없이 문을 밀어요", "열쇠를 얻는 장면이 없어요.", "서윤은 등불을 바닥에 내려놓고 천천히 문을 밀었다.", "천천히 문을 밀었다",
            new SettingCheckData.Source(PLACE, "북쪽 온실", "place", null, "북쪽 문은 낡은 열쇠 없이는 열리지 않는다.", "낡은 열쇠 없이는 열리지 않는다"), null, null);
    static final SettingCheckData.Finding INTENDED = new SettingCheckData.Finding(FINDING, "MANUSCRIPT", false, "LOW",
            "9화에서는 하린이 왼손잡이였어요", "9화의 해당 문단을 확인해 보세요.", "하린은 오른손으로 칼을 쥐었다.", null,
            new SettingCheckData.Source(EARLIER, "9화", "manuscript", 9, "왼손잡이인 하린은 늘 칼을 왼쪽에 찼다.", null), "INTENDED", "하린은 일부러 손을 바꿨어요.");
    static final SettingCheckData.Run DONE = new SettingCheckData.Run(RUN, PROJECT, CHAPTER, "12화 · 균열의 밤", 14L, "DONE", null, STARTED, FINISHED,
            new SettingCheckData.Read(11, 1, 11, 21, 6), new SettingCheckData.Counts(3, 1, 0), null, null);
    static final SettingCheckData.Run RUNNING = new SettingCheckData.Run(RUN, PROJECT, CHAPTER, "12화 · 균열의 밤", 14L, "RUNNING", "READING", STARTED, null,
            new SettingCheckData.Read(0, null, null, 0, 0), new SettingCheckData.Counts(0, 0, 0), null, null);
    static final String DONE_JSON = "{\"id\":\"" + RUN + "\",\"project_id\":\"" + PROJECT + "\",\"document_id\":\"" + CHAPTER + "\","
            + "\"document_title\":\"12화 · 균열의 밤\",\"revision_no\":14,\"status\":\"DONE\",\"stage\":null,"
            + "\"created_at\":\"2026-10-27T12:40:00Z\",\"finished_at\":\"2026-10-27T12:43:10Z\","
            + "\"read\":{\"manuscripts\":11,\"from_ordinal\":1,\"to_ordinal\":11,\"settings\":21,\"core_facts\":6},"
            + "\"counts\":{\"open\":3,\"intended\":1,\"not_conflict\":0},\"failure_code\":null}";
    static final String RUNNING_JSON = "{\"id\":\"" + RUN + "\",\"project_id\":\"" + PROJECT + "\",\"document_id\":\"" + CHAPTER + "\","
            + "\"document_title\":\"12화 · 균열의 밤\",\"revision_no\":14,\"status\":\"RUNNING\",\"stage\":\"READING\","
            + "\"created_at\":\"2026-10-27T12:40:00Z\",\"finished_at\":null,"
            + "\"read\":{\"manuscripts\":0,\"from_ordinal\":null,\"to_ordinal\":null,\"settings\":0,\"core_facts\":0},"
            + "\"counts\":{\"open\":0,\"intended\":0,\"not_conflict\":0},\"failure_code\":null}";
    static final String CORE_JSON = "{\"id\":\"" + FINDING + "\",\"kind\":\"SETTING\",\"core\":true,\"confidence\":\"HIGH\","
            + "\"summary\":\"북쪽 문은 낡은 열쇠로만 열리는데, 서윤이 열쇠 없이 문을 밀어요\",\"direction\":\"열쇠를 얻는 장면이 없어요.\","
            + "\"quote\":\"서윤은 등불을 바닥에 내려놓고 천천히 문을 밀었다.\",\"quote_focus\":\"천천히 문을 밀었다\","
            + "\"source\":{\"document_id\":\"" + PLACE + "\",\"title\":\"북쪽 온실\",\"doc_type\":\"place\",\"ordinal\":null,"
            + "\"quote\":\"북쪽 문은 낡은 열쇠 없이는 열리지 않는다.\",\"quote_focus\":\"낡은 열쇠 없이는 열리지 않는다\"},\"verdict\":null,\"reason\":null}";
    static final String INTENDED_JSON = "{\"id\":\"" + FINDING + "\",\"kind\":\"MANUSCRIPT\",\"core\":false,\"confidence\":\"LOW\","
            + "\"summary\":\"9화에서는 하린이 왼손잡이였어요\",\"direction\":\"9화의 해당 문단을 확인해 보세요.\",\"quote\":\"하린은 오른손으로 칼을 쥐었다.\",\"quote_focus\":null,"
            + "\"source\":{\"document_id\":\"" + EARLIER + "\",\"title\":\"9화\",\"doc_type\":\"manuscript\",\"ordinal\":9,"
            + "\"quote\":\"왼손잡이인 하린은 늘 칼을 왼쪽에 찼다.\",\"quote_focus\":null},\"verdict\":\"INTENDED\",\"reason\":\"하린은 일부러 손을 바꿨어요.\"}";
    static final String START_BODY = "{\"document_id\":\"" + CHAPTER + "\"}";
    static final String VERDICT_BODY = "{\"verdict\":\"INTENDED\",\"reason\":\"하린은 일부러 손을 바꿨어요.\"}";

    @Autowired MockMvc mvc;
    @MockitoBean OpaqueSessionVerifier verifier;
    @MockitoBean ChatApiClient client;
    @MockitoBean AccountService accounts;

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean CookieSettings names() { return new CookieSettings("__Host-ls_oauth", true); }
        @Bean AuthCookies cookies(CookieSettings names, Clock clock) { return new AuthCookies(names, clock); }
        @Bean @Order(1) CsrfFilter csrf() { var cors = new CorsConfiguration(); cors.setAllowedOrigins(List.of("https://loresentry.com")); return new CsrfFilter(cors); }
        @Bean @Order(2) SessionFilter session(OpaqueSessionVerifier verifier, CookieSettings names, AuthCookies cookies) { return new SessionFilter(verifier, names, cookies); }
        @Bean ChatAllowlist chatAllowlist() { return ChatAllowlist.parse(WRITER.toString()); }
    }

    @BeforeEach void sessions() {
        when(verifier.verify(WRITER_SESSION)).thenReturn(new VerifiedSession(WRITER, NOW.plusSeconds(1209600)));
        when(verifier.verify(OUTSIDER_SESSION)).thenReturn(new VerifiedSession(OUTSIDER, NOW.plusSeconds(1209600)));
        when(verifier.verify(null)).thenThrow(new SecurityFailure(SecurityFailure.Reason.SESSION_REQUIRED));
    }

    static MockHttpServletRequestBuilder as(String session, MockHttpServletRequestBuilder request) {
        return request.header("Origin", "https://loresentry.com").header("X-LS-CSRF", "1").cookie(new Cookie("__Host-ls_session", session));
    }

    static MockHttpServletRequestBuilder route(String route) {
        var parts = route.split(" ");
        var uri = parts[1].replace("{p}", PROJECT.toString()).replace("{r}", RUN.toString()).replace("{f}", FINDING.toString()).replace("{d}", CHAPTER.toString());
        var request = request(HttpMethod.valueOf(parts[0]), uri);
        if (parts[0].equals("POST") && uri.endsWith("/setting-checks")) request.contentType(MediaType.APPLICATION_JSON).content(START_BODY);
        if (parts[0].equals("PUT")) request.contentType(MediaType.APPLICATION_JSON).content(VERDICT_BODY);
        return request;
    }

    static String withFindings(String run, String... findings) {
        return run.substring(0, run.length() - 1) + ",\"findings\":[" + String.join(",", findings) + "]}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /projects/{p}/setting-checks", "GET /projects/{p}/setting-checks/summary", "GET /projects/{p}/setting-checks?document_id={d}",
        "GET /setting-checks/{r}", "POST /setting-checks/{r}/cancel", "PUT /setting-check-findings/{f}/verdict", "DELETE /setting-check-findings/{f}/verdict"})
    void everyRouteTurnsAwayUsersOutsideTheAllowlist(String route) throws Exception {
        mvc.perform(as(OUTSIDER_SESSION, route(route)))
            .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("CHAT_NOT_ENABLED")).andExpect(jsonPath("$.next_action").value("NONE"))
            .andExpect(jsonPath("$.message").isString()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Set-Cookie"));
        verifyNoInteractions(client, accounts);
    }

    @Test void startIsAcceptedWithTheRunAndForwardsOnlyTheLocale() throws Exception {
        when(accounts.get(WRITER)).thenReturn(new AccountService.Account(WRITER, "유리 작가", "writer@example.test", true, "ko"));
        when(client.startSettingCheck(WRITER, PROJECT, new SettingCheckData.Start(CHAPTER), new ChatData.Profile("ko", null))).thenReturn(RUNNING);
        mvc.perform(as(WRITER_SESSION, post("/projects/" + PROJECT + "/setting-checks")).contentType(MediaType.APPLICATION_JSON).content(START_BODY))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json(RUNNING_JSON, JsonCompareMode.STRICT));
        verify(client).startSettingCheck(WRITER, PROJECT, new SettingCheckData.Start(CHAPTER), new ChatData.Profile("ko", null));
    }

    @Test void startContinuesWithoutTheLocaleWhenAuthFails() throws Exception {
        when(accounts.get(WRITER)).thenThrow(new AuthOperationFailure(503, "ACCOUNT_UNAVAILABLE", "RETRY_LATER"));
        when(client.startSettingCheck(WRITER, PROJECT, new SettingCheckData.Start(CHAPTER), null)).thenReturn(RUNNING);
        mvc.perform(as(WRITER_SESSION, post("/projects/" + PROJECT + "/setting-checks")).contentType(MediaType.APPLICATION_JSON).content(START_BODY))
            .andExpect(status().isAccepted());
        verify(client).startSettingCheck(WRITER, PROJECT, new SettingCheckData.Start(CHAPTER), null);
    }

    @Test void summaryKeepsTheRunningRunAndEachChapterWithoutFindings() throws Exception {
        when(client.settingCheckSummary(WRITER, PROJECT)).thenReturn(
            new SettingCheckData.Summary(RUNNING, List.of(new SettingCheckData.Chapter(CHAPTER, DONE))),
            new SettingCheckData.Summary(null, List.of()));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/setting-checks/summary"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"running\":" + RUNNING_JSON + ",\"chapters\":[{\"document_id\":\"" + CHAPTER + "\",\"latest\":" + DONE_JSON + "}]}",
                JsonCompareMode.STRICT));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/setting-checks/summary"))).andExpect(status().isOk())
            .andExpect(content().json("{\"running\":null,\"chapters\":[]}", JsonCompareMode.STRICT));
        verifyNoInteractions(accounts);
    }

    @Test void runsOfADocumentUseTheBrowserShape() throws Exception {
        var failed = new SettingCheckData.Run(RUN, PROJECT, CHAPTER, "12화 · 균열의 밤", 13L, "FAILED", null, STARTED, FINISHED,
            new SettingCheckData.Read(11, 1, 11, 21, 6), new SettingCheckData.Counts(0, 0, 0), "CHAT_MODEL_UNAVAILABLE", List.of(CORE));
        when(client.settingChecks(WRITER, PROJECT, CHAPTER)).thenReturn(new SettingCheckData.Runs(List.of(DONE, failed)));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/setting-checks").param("document_id", CHAPTER.toString()))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"runs\":[" + DONE_JSON + "," + DONE_JSON.replace("\"revision_no\":14,\"status\":\"DONE\"", "\"revision_no\":13,\"status\":\"FAILED\"")
                .replace("\"open\":3,\"intended\":1", "\"open\":0,\"intended\":0").replace("\"failure_code\":null", "\"failure_code\":\"CHAT_MODEL_UNAVAILABLE\"") + "]}",
                JsonCompareMode.STRICT));
        verifyNoInteractions(accounts);
    }

    @Test void oneRunCarriesItsFindings() throws Exception {
        var done = new SettingCheckData.Run(RUN, PROJECT, CHAPTER, "12화 · 균열의 밤", 14L, "DONE", null, STARTED, FINISHED,
            new SettingCheckData.Read(11, 1, 11, 21, 6), new SettingCheckData.Counts(3, 1, 0), null, List.of(CORE, INTENDED));
        when(client.settingCheck(WRITER, RUN)).thenReturn(done);
        mvc.perform(as(WRITER_SESSION, get("/setting-checks/" + RUN))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json(withFindings(DONE_JSON, CORE_JSON, INTENDED_JSON), JsonCompareMode.STRICT));
        when(client.settingCheck(WRITER, RUN)).thenReturn(new SettingCheckData.Run(RUN, PROJECT, CHAPTER, "12화 · 균열의 밤", 14L, "RUNNING", "READING",
            STARTED, null, new SettingCheckData.Read(0, null, null, 0, 0), new SettingCheckData.Counts(0, 0, 0), null, List.of()));
        mvc.perform(as(WRITER_SESSION, get("/setting-checks/" + RUN))).andExpect(status().isOk())
            .andExpect(content().json(withFindings(RUNNING_JSON), JsonCompareMode.STRICT));
        verifyNoInteractions(accounts);
    }

    @Test void cancelHasNoContent() throws Exception {
        mvc.perform(as(WRITER_SESSION, post("/setting-checks/" + RUN + "/cancel"))).andExpect(status().isNoContent())
            .andExpect(content().string("")).andExpect(header().string("Cache-Control", "no-store"));
        verify(client).cancelSettingCheck(WRITER, RUN);
        verifyNoInteractions(accounts);
    }

    @Test void verdictsAreSetAndClearedOnTheFinding() throws Exception {
        when(client.setSettingCheckVerdict(WRITER, FINDING, new SettingCheckData.Verdict("INTENDED", "하린은 일부러 손을 바꿨어요."))).thenReturn(INTENDED);
        when(client.setSettingCheckVerdict(WRITER, FINDING, new SettingCheckData.Verdict("NOT_CONFLICT", null)))
            .thenReturn(new SettingCheckData.Finding(FINDING, "SETTING", true, "HIGH", CORE.summary(), CORE.direction(), CORE.quote(), CORE.quoteFocus(), CORE.source(), "NOT_CONFLICT", null));
        when(client.clearSettingCheckVerdict(WRITER, FINDING)).thenReturn(CORE);
        mvc.perform(as(WRITER_SESSION, put("/setting-check-findings/" + FINDING + "/verdict")).contentType(MediaType.APPLICATION_JSON).content(VERDICT_BODY))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json(INTENDED_JSON, JsonCompareMode.STRICT));
        mvc.perform(as(WRITER_SESSION, put("/setting-check-findings/" + FINDING + "/verdict")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verdict\":\"NOT_CONFLICT\",\"reason\":null}"))
            .andExpect(status().isOk()).andExpect(content().json(CORE_JSON.replace("\"verdict\":null", "\"verdict\":\"NOT_CONFLICT\""), JsonCompareMode.STRICT));
        mvc.perform(as(WRITER_SESSION, delete("/setting-check-findings/" + FINDING + "/verdict")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json(CORE_JSON, JsonCompareMode.STRICT));
        verify(client).setSettingCheckVerdict(WRITER, FINDING, new SettingCheckData.Verdict("INTENDED", "하린은 일부러 손을 바꿨어요."));
        verify(client).setSettingCheckVerdict(WRITER, FINDING, new SettingCheckData.Verdict("NOT_CONFLICT", null));
        verify(client).clearSettingCheckVerdict(WRITER, FINDING);
        verifyNoInteractions(accounts);
    }

    @ParameterizedTest
    @CsvSource({"404,SETTING_CHECK_NOT_FOUND,NONE,The setting check was not found.", "404,SETTING_CHECK_FINDING_NOT_FOUND,NONE,The setting check finding was not found.",
        "404,SETTING_CHECK_DOCUMENT_NOT_FOUND,NONE,The chapter to check was not found.", "409,SETTING_CHECK_IN_PROGRESS,NONE,A setting check is already running in this project.",
        "404,PROJECT_NOT_FOUND,NONE,The requested resource was not found.", "400,INVALID_REQUEST,NONE,Invalid request.",
        "503,CONTENT_UNAVAILABLE,RETRY_LATER,Content service is unavailable.", "503,AI_CHAT_UNAVAILABLE,RETRY_LATER,AI chat service is unavailable.",
        "502,UPSTREAM_INVALID_RESPONSE,NONE,The service returned an invalid response."})
    void startFailuresUseTheCommonErrorBody(int status, String code, String action, String message) throws Exception {
        when(accounts.get(WRITER)).thenReturn(new AccountService.Account(WRITER, "유리 작가", "writer@example.test", true, "ko"));
        when(client.startSettingCheck(eq(WRITER), eq(PROJECT), any(), any())).thenThrow(new ChatCallFailure(status, code, action));
        mvc.perform(as(WRITER_SESSION, post("/projects/" + PROJECT + "/setting-checks")).contentType(MediaType.APPLICATION_JSON).content(START_BODY))
            .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.next_action").value(action))
            .andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.length()").value(3))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void runAndVerdictFailuresKeepTheirCodes() throws Exception {
        when(client.settingCheck(WRITER, RUN)).thenThrow(new ChatCallFailure(404, "SETTING_CHECK_NOT_FOUND", "NONE"));
        when(client.clearSettingCheckVerdict(WRITER, FINDING)).thenThrow(new ChatCallFailure(404, "SETTING_CHECK_FINDING_NOT_FOUND", "NONE"));
        when(client.setSettingCheckVerdict(eq(WRITER), eq(FINDING), any())).thenThrow(new ChatCallFailure(400, "INVALID_REQUEST", "NONE"));
        mvc.perform(as(WRITER_SESSION, get("/setting-checks/" + RUN)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTING_CHECK_NOT_FOUND"));
        mvc.perform(as(WRITER_SESSION, delete("/setting-check-findings/" + FINDING + "/verdict")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTING_CHECK_FINDING_NOT_FOUND"));
        mvc.perform(as(WRITER_SESSION, put("/setting-check-findings/" + FINDING + "/verdict")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verdict\":\"MAYBE\",\"reason\":null}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(client).setSettingCheckVerdict(WRITER, FINDING, new SettingCheckData.Verdict("MAYBE", null));
    }

    @Test void malformedIdsAreNotFoundAndMalformedDocumentFiltersAreInvalid() throws Exception {
        mvc.perform(as(WRITER_SESSION, get("/projects/not-a-uuid/setting-checks/summary")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mvc.perform(as(WRITER_SESSION, get("/setting-checks/not-a-uuid")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTING_CHECK_NOT_FOUND"));
        mvc.perform(as(WRITER_SESSION, post("/setting-checks/not-a-uuid/cancel")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTING_CHECK_NOT_FOUND"));
        mvc.perform(as(WRITER_SESSION, delete("/setting-check-findings/not-a-uuid/verdict")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTING_CHECK_FINDING_NOT_FOUND"));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/setting-checks").param("document_id", "not-a-uuid")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(as(WRITER_SESSION, get("/projects/" + PROJECT + "/setting-checks")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.next_action").value("NONE")).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(client, accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /projects/{p}/setting-checks|", "POST /projects/{p}/setting-checks|{", "POST /projects/{p}/setting-checks|{\"document_id\":\"x\"}",
        "POST /projects/{p}/setting-checks|{\"document_id\":1}", "POST /projects/{p}/setting-checks|{\"document_id\":\"" + "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d71" + "\",\"user_id\":\"x\"}",
        "POST /projects/{p}/setting-checks|{} {}", "PUT /setting-check-findings/{f}/verdict|", "PUT /setting-check-findings/{f}/verdict|{\"verdict\":1}",
        "PUT /setting-check-findings/{f}/verdict|{\"verdict\":\"INTENDED\",\"reason\":5}", "PUT /setting-check-findings/{f}/verdict|{\"verdict\":\"INTENDED\",\"run_id\":\"x\"}"})
    void malformedBodiesAreRejected(String input) throws Exception {
        var parts = input.split("\\|", -1);
        mvc.perform(as(WRITER_SESSION, route(parts[0])).contentType(MediaType.APPLICATION_JSON).content(parts[1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(client, accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /projects/{p}/setting-checks", "POST /setting-checks/{r}/cancel", "PUT /setting-check-findings/{f}/verdict",
        "DELETE /setting-check-findings/{f}/verdict"})
    void writesRequireCsrf(String route) throws Exception {
        mvc.perform(route(route).cookie(new Cookie("__Host-ls_session", WRITER_SESSION)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        verifyNoInteractions(client, accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/projects/{p}/setting-checks/summary", "/projects/{p}/setting-checks?document_id={d}", "/setting-checks/{r}"})
    void readsRequireASession(String path) throws Exception {
        mvc.perform(get(path.replace("{p}", PROJECT.toString()).replace("{d}", CHAPTER.toString()).replace("{r}", RUN.toString())))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        verifyNoInteractions(client, accounts);
    }
}
