package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.loresentry.gateway.client.chat.ChatApiClient;
import com.loresentry.gateway.client.chat.ChatCallFailure;
import com.loresentry.gateway.client.chat.ChatData;
import com.loresentry.gateway.client.chat.SettingCheckData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SettingCheckApiClientTest {
    static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID PROJECT = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID RUN = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d70");
    static final UUID CHAPTER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d71");
    static final UUID FINDING = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d72");
    static final UUID PLACE = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d73");
    static final UUID EARLIER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d74");
    static final String RUN_JSON = "{\"id\":\"" + RUN + "\",\"project_id\":\"" + PROJECT + "\",\"document_id\":\"" + CHAPTER + "\","
            + "\"document_title\":\"12화 · 균열의 밤\",\"revision_no\":14,\"status\":\"DONE\",\"stage\":null,"
            + "\"created_at\":\"2026-10-27T12:40:00.000Z\",\"finished_at\":\"2026-10-27T12:43:10.000Z\","
            + "\"read\":{\"manuscripts\":11,\"from_ordinal\":1,\"to_ordinal\":11,\"settings\":21,\"core_facts\":6},"
            + "\"counts\":{\"open\":3,\"intended\":1,\"not_conflict\":0},\"failure_code\":null}";
    static final String RUNNING_JSON = "{\"id\":\"" + RUN + "\",\"project_id\":\"" + PROJECT + "\",\"document_id\":\"" + CHAPTER + "\","
            + "\"document_title\":\"12화 · 균열의 밤\",\"revision_no\":14,\"status\":\"RUNNING\",\"stage\":\"READING\","
            + "\"created_at\":\"2026-10-27T12:40:00Z\",\"finished_at\":null,"
            + "\"read\":{\"manuscripts\":0,\"from_ordinal\":null,\"to_ordinal\":null,\"settings\":0,\"core_facts\":0},"
            + "\"counts\":{\"open\":0,\"intended\":0,\"not_conflict\":0},\"failure_code\":null}";
    static final String CORE_FINDING_JSON = "{\"id\":\"" + FINDING + "\",\"kind\":\"SETTING\",\"core\":true,\"confidence\":\"HIGH\","
            + "\"summary\":\"북쪽 문은 낡은 열쇠로만 열리는데, 서윤이 열쇠 없이 문을 밀어요\",\"direction\":\"열쇠를 얻는 장면이 없어요.\","
            + "\"quote\":\"서윤은 등불을 바닥에 내려놓고 천천히 문을 밀었다.\",\"quote_focus\":\"천천히 문을 밀었다\","
            + "\"source\":{\"document_id\":\"" + PLACE + "\",\"title\":\"북쪽 온실\",\"doc_type\":\"place\",\"ordinal\":null,"
            + "\"quote\":\"북쪽 문은 낡은 열쇠 없이는 열리지 않는다.\",\"quote_focus\":\"낡은 열쇠 없이는 열리지 않는다\"},\"verdict\":null,\"reason\":null}";
    static final String MANUSCRIPT_FINDING_JSON = "{\"id\":\"" + FINDING + "\",\"kind\":\"MANUSCRIPT\",\"core\":false,\"confidence\":\"LOW\","
            + "\"summary\":\"9화에서는 하린이 왼손잡이였어요\",\"direction\":\"9화의 해당 문단을 확인해 보세요.\",\"quote\":\"하린은 오른손으로 칼을 쥐었다.\",\"quote_focus\":null,"
            + "\"source\":{\"document_id\":\"" + EARLIER + "\",\"title\":\"9화\",\"doc_type\":\"manuscript\",\"ordinal\":9,"
            + "\"quote\":\"왼손잡이인 하린은 늘 칼을 왼쪽에 찼다.\",\"quote_focus\":null},\"verdict\":\"INTENDED\",\"reason\":\"하린은 일부러 손을 바꿨어요.\"}";
    static final SettingCheckData.Read READ = new SettingCheckData.Read(11, 1, 11, 21, 6);
    static final SettingCheckData.Counts COUNTS = new SettingCheckData.Counts(3, 1, 0);
    MockRestServiceServer server;
    ChatApiClient client;

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("http://chat.test");
        var mocks = new MockServerRestClientCustomizer();
        mocks.customize(builder);
        server = mocks.getServer();
        client = new ChatApiClient(builder.build(), null);
    }

    static String withFindings(String run, String... findings) {
        return run.substring(0, run.length() - 1) + ",\"findings\":[" + String.join(",", findings) + "]}";
    }

    @Test void startsWithTheDocumentAndOnlyTheLocale() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(header("X-User-Locale", "ko")).andExpect(headerDoesNotExist("X-User-Display-Name"))
            .andExpect(headerDoesNotExist("Cookie")).andExpect(content().json("{\"document_id\":\"" + CHAPTER + "\"}", JsonCompareMode.STRICT))
            .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(RUNNING_JSON));
        var run = client.startSettingCheck(USER, PROJECT, new SettingCheckData.Start(CHAPTER), new ChatData.Profile("ko", null));
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.stage()).isEqualTo("READING");
        assertThat(run.finishedAt()).isNull();
        assertThat(run.read()).isEqualTo(new SettingCheckData.Read(0, null, null, 0, 0));
        assertThat(run.findings()).isNull();
        server.verify();
    }

    @Test void startWithoutProfileSendsNoLocale() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks")).andExpect(method(HttpMethod.POST))
            .andExpect(headerDoesNotExist("X-User-Locale")).andExpect(headerDoesNotExist("X-User-Display-Name"))
            .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(RUNNING_JSON));
        client.startSettingCheck(USER, PROJECT, new SettingCheckData.Start(CHAPTER), null);
        server.verify();
    }

    @Test void readsTheSummaryWithTheRunningRunAndEachChapter() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks/summary")).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("X-User-Locale"))
            .andRespond(withSuccess("{\"running\":" + RUNNING_JSON + ",\"chapters\":[{\"document_id\":\"" + CHAPTER + "\",\"latest\":" + RUN_JSON + "}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks/summary"))
            .andRespond(withSuccess("{\"running\":null,\"chapters\":[]}", MediaType.APPLICATION_JSON));
        var summary = client.settingCheckSummary(USER, PROJECT);
        assertThat(summary.running().status()).isEqualTo("RUNNING");
        assertThat(summary.chapters()).singleElement().satisfies(chapter -> {
            assertThat(chapter.documentId()).isEqualTo(CHAPTER);
            assertThat(chapter.latest().read()).isEqualTo(READ);
            assertThat(chapter.latest().counts()).isEqualTo(COUNTS);
            assertThat(chapter.latest().revisionNo()).isEqualTo(14L);
            assertThat(chapter.latest().finishedAt()).isEqualTo(OffsetDateTime.parse("2026-10-27T12:43:10Z"));
        });
        assertThat(client.settingCheckSummary(USER, PROJECT)).isEqualTo(new SettingCheckData.Summary(null, java.util.List.of()));
        server.verify();
    }

    @Test void listsTheRunsOfOneDocument() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks?document_id=" + CHAPTER)).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-User-Id", USER.toString()))
            .andRespond(withSuccess("{\"runs\":[" + RUN_JSON + "," + RUNNING_JSON.replace("\"RUNNING\",\"stage\":\"READING\"", "\"FAILED\",\"stage\":null")
                .replace("\"failure_code\":null", "\"failure_code\":\"CHAT_MODEL_UNAVAILABLE\"") + "]}", MediaType.APPLICATION_JSON));
        var runs = client.settingChecks(USER, PROJECT, CHAPTER).runs();
        assertThat(runs).extracting(SettingCheckData.Run::status).containsExactly("DONE", "FAILED");
        assertThat(runs.get(1).failureCode()).isEqualTo("CHAT_MODEL_UNAVAILABLE");
        server.verify();
    }

    @Test void readsOneRunWithItsFindings() {
        server.expect(requestTo("http://chat.test/setting-checks/" + RUN)).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-User-Id", USER.toString()))
            .andRespond(withSuccess(withFindings(RUN_JSON, CORE_FINDING_JSON, MANUSCRIPT_FINDING_JSON), MediaType.APPLICATION_JSON));
        var findings = client.settingCheck(USER, RUN).findings();
        assertThat(findings).containsExactly(
            new SettingCheckData.Finding(FINDING, "SETTING", true, "HIGH", "북쪽 문은 낡은 열쇠로만 열리는데, 서윤이 열쇠 없이 문을 밀어요",
                "열쇠를 얻는 장면이 없어요.", "서윤은 등불을 바닥에 내려놓고 천천히 문을 밀었다.", "천천히 문을 밀었다",
                new SettingCheckData.Source(PLACE, "북쪽 온실", "place", null, "북쪽 문은 낡은 열쇠 없이는 열리지 않는다.", "낡은 열쇠 없이는 열리지 않는다"),
                null, null),
            new SettingCheckData.Finding(FINDING, "MANUSCRIPT", false, "LOW", "9화에서는 하린이 왼손잡이였어요", "9화의 해당 문단을 확인해 보세요.",
                "하린은 오른손으로 칼을 쥐었다.", null, new SettingCheckData.Source(EARLIER, "9화", "manuscript", 9, "왼손잡이인 하린은 늘 칼을 왼쪽에 찼다.", null),
                "INTENDED", "하린은 일부러 손을 바꿨어요."));
        server.verify();
    }

    @Test void findingsWithoutQuoteFocusStillRead() {
        var finding = CORE_FINDING_JSON.replace(",\"quote_focus\":\"천천히 문을 밀었다\"", "").replace(",\"quote_focus\":\"낡은 열쇠 없이는 열리지 않는다\"", "");
        assertThat(finding).doesNotContain("quote_focus");
        server.expect(requestTo("http://chat.test/setting-checks/" + RUN)).andRespond(withSuccess(withFindings(RUN_JSON, finding), MediaType.APPLICATION_JSON));
        assertThat(client.settingCheck(USER, RUN).findings()).singleElement().satisfies(read -> {
            assertThat(read.quoteFocus()).isNull();
            assertThat(read.source().quoteFocus()).isNull();
        });
        server.verify();
    }

    @Test void runWithoutFindingsIsInvalid() {
        server.expect(requestTo("http://chat.test/setting-checks/" + RUN)).andRespond(withSuccess(RUN_JSON, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.settingCheck(USER, RUN)).isInstanceOf(ChatCallFailure.class).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @Test void cancelsWithNoContent() {
        server.expect(requestTo("http://chat.test/setting-checks/" + RUN + "/cancel")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(content().string("")).andRespond(withNoContent());
        client.cancelSettingCheck(USER, RUN);
        server.verify();
    }

    @Test void setsAndClearsAVerdict() {
        server.expect(requestTo("http://chat.test/setting-check-findings/" + FINDING + "/verdict")).andExpect(method(HttpMethod.PUT))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(headerDoesNotExist("X-User-Locale"))
            .andExpect(content().json("{\"verdict\":\"NOT_CONFLICT\",\"reason\":null}", JsonCompareMode.STRICT))
            .andRespond(withSuccess(CORE_FINDING_JSON.replace("\"verdict\":null", "\"verdict\":\"NOT_CONFLICT\""), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://chat.test/setting-check-findings/" + FINDING + "/verdict")).andExpect(method(HttpMethod.DELETE))
            .andExpect(header("X-User-Id", USER.toString())).andExpect(content().string(""))
            .andRespond(withSuccess(CORE_FINDING_JSON, MediaType.APPLICATION_JSON));
        assertThat(client.setSettingCheckVerdict(USER, FINDING, new SettingCheckData.Verdict("NOT_CONFLICT", null)).verdict()).isEqualTo("NOT_CONFLICT");
        assertThat(client.clearSettingCheckVerdict(USER, FINDING).verdict()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"404,SETTING_CHECK_NOT_FOUND,404,SETTING_CHECK_NOT_FOUND,NONE", "404,SETTING_CHECK_FINDING_NOT_FOUND,404,SETTING_CHECK_FINDING_NOT_FOUND,NONE",
        "404,SETTING_CHECK_DOCUMENT_NOT_FOUND,404,SETTING_CHECK_DOCUMENT_NOT_FOUND,NONE", "409,SETTING_CHECK_IN_PROGRESS,409,SETTING_CHECK_IN_PROGRESS,NONE",
        "404,PROJECT_NOT_FOUND,404,PROJECT_NOT_FOUND,NONE", "400,INVALID_REQUEST,400,INVALID_REQUEST,NONE",
        "503,CONTENT_UNAVAILABLE,503,CONTENT_UNAVAILABLE,RETRY_LATER", "503,CHAT_MODEL_UNAVAILABLE,503,CHAT_MODEL_UNAVAILABLE,RETRY_LATER",
        "409,SETTING_CHECK_DOCUMENT_NOT_FOUND,502,UPSTREAM_INVALID_RESPONSE,NONE", "404,SETTING_CHECK_IN_PROGRESS,502,UPSTREAM_INVALID_RESPONSE,NONE",
        "401,USER_CONTEXT_REQUIRED,502,UPSTREAM_INVALID_RESPONSE,NONE", "500,INTERNAL_ERROR,502,UPSTREAM_INVALID_RESPONSE,NONE"})
    void passesThroughOnlyContractedErrors(int status, String code, int external, String result, String action) {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks")).andRespond(withStatus(HttpStatusCode.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"" + code + "\",\"message\":\"private detail\",\"next_action\":\"NONE\"}"));
        var failure = catchThrowableOfType(ChatCallFailure.class, () -> client.startSettingCheck(USER, PROJECT, new SettingCheckData.Start(CHAPTER), null));
        assertThat(failure.status()).isEqualTo(external);
        assertThat(failure.code()).isEqualTo(result);
        assertThat(failure.nextAction()).isEqualTo(action);
        assertThat(failure.getMessage()).doesNotContain("private");
        server.verify();
    }

    @Test void lostResponseIsUnavailable() {
        server.expect(requestTo("http://chat.test/setting-checks/" + RUN)).andRespond(withException(new java.net.SocketTimeoutException("private")));
        var failure = catchThrowableOfType(ChatCallFailure.class, () -> client.settingCheck(USER, RUN));
        assertThat(failure.status()).isEqualTo(503);
        assertThat(failure.code()).isEqualTo("AI_CHAT_UNAVAILABLE");
        assertThat(failure.nextAction()).isEqualTo("RETRY_LATER");
        server.verify();
    }

    @Test void startAnsweredWithOkInsteadOfAcceptedIsInvalid() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks")).andRespond(withSuccess(RUNNING_JSON, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.startSettingCheck(USER, PROJECT, new SettingCheckData.Start(CHAPTER), null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"status\":\"DONE\"|\"status\":\"PAUSED\"", "\"stage\":null|\"stage\":\"WRITING\"", "\"revision_no\":14|\"revision_no\":\"14\"",
        "\"revision_no\":14|\"revision_no\":-1", "\"created_at\":\"2026-10-27T12:40:00.000Z\"|\"created_at\":null", "\"document_title\":\"12화 · 균열의 밤\",|",
        "\"read\":{\"manuscripts\":11,\"from_ordinal\":1,\"to_ordinal\":11,\"settings\":21,\"core_facts\":6}|\"read\":null",
        "\"manuscripts\":11,|", "\"core_facts\":6|\"core_facts\":-1", "\"from_ordinal\":1|\"from_ordinal\":1.5",
        "\"counts\":{\"open\":3,\"intended\":1,\"not_conflict\":0}|\"counts\":{\"open\":3,\"intended\":1}", "\"open\":3|\"open\":-3",
        "\"project_id\":\"" + "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61" + "\"|\"project_id\":\"x\"", "\"failure_code\":null|\"failure_code\":1"})
    void rejectsRunsOutsideTheContract(String replacement) {
        var parts = replacement.split("\\|", -1);
        var run = RUN_JSON.replace(parts[0], parts[1]);
        assertThat(run).isNotEqualTo(RUN_JSON);
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks?document_id=" + CHAPTER))
            .andRespond(withSuccess("{\"runs\":[" + run + "]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.settingChecks(USER, PROJECT, CHAPTER)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"kind\":\"SETTING\"|\"kind\":\"PLOT\"", "\"confidence\":\"HIGH\"|\"confidence\":\"MEDIUM\"", "\"verdict\":null|\"verdict\":\"IGNORED\"",
        "\"core\":true|\"core\":\"true\"", "\"core\":true,|", "\"summary\":\"북쪽 문은 낡은 열쇠로만 열리는데, 서윤이 열쇠 없이 문을 밀어요\"|\"summary\":null",
        "\"direction\":\"열쇠를 얻는 장면이 없어요.\",|", "\"quote\":\"서윤은 등불을 바닥에 내려놓고 천천히 문을 밀었다.\"|\"quote\":null",
        "\"source\":{\"document_id\"|\"source\":null,\"x\":{\"document_id\"", "\"doc_type\":\"place\",|", "\"ordinal\":null|\"ordinal\":\"1\"",
        "\"quote\":\"북쪽 문은 낡은 열쇠 없이는 열리지 않는다.\"|\"quote\":null", "\"document_id\":\"" + "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d73" + "\"|\"document_id\":null"})
    void rejectsFindingsOutsideTheContract(String replacement) {
        var parts = replacement.split("\\|", -1);
        var finding = CORE_FINDING_JSON.replace(parts[0], parts[1]);
        assertThat(finding).isNotEqualTo(CORE_FINDING_JSON);
        server.expect(requestTo("http://chat.test/setting-checks/" + RUN)).andRespond(withSuccess(withFindings(RUN_JSON, finding), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.settingCheck(USER, RUN)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"running\":null}", "{\"running\":null,\"chapters\":null}", "{\"running\":null,\"chapters\":[null]}",
        "{\"running\":null,\"chapters\":[{\"document_id\":null,\"latest\":null}]}", "{\"running\":{},\"chapters\":[]}", "{\"running\":null,\"chapters\":[]} {}"})
    void rejectsMalformedSummaries(String payload) {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks/summary")).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.settingCheckSummary(USER, PROJECT)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        server.verify();
    }

    @Test void ignoresUncontractedFieldsInRuns() {
        server.expect(requestTo("http://chat.test/projects/" + PROJECT + "/setting-checks?document_id=" + CHAPTER))
            .andRespond(withSuccess("{\"runs\":[" + RUN_JSON.replace("{\"id\"", "{\"user_id\":\"" + USER + "\",\"model\":\"m\",\"input_tokens\":10,\"id\"") + "]}",
                MediaType.APPLICATION_JSON));
        assertThat(client.settingChecks(USER, PROJECT, CHAPTER).runs()).extracting(SettingCheckData.Run::id).containsExactly(RUN);
        server.verify();
    }
}
