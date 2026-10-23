package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import com.loresentry.gateway.client.content.ContentCallFailure;
import com.loresentry.gateway.client.content.ContentData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class ContentApiClientTest {
    @Test void encodesSearchExactlyOnceAndOnlyForwardsExplicitIdentity() {
        var builder = RestClient.builder().baseUrl("http://content.test");
        var mocks = new MockServerRestClientCustomizer();mocks.customize(builder);
        var server = mocks.getServer();
        var service = new ContentService(new ContentApiClient(builder.build()), null);
        var user = UUID.randomUUID();var project = UUID.randomUUID();
        server.expect(requestTo("http://content.test/projects/"+project+"/search?q=%EC%9C%A0%EB%A6%AC%20%26%20%2B%3F"))
            .andExpect(header("X-User-Id",user.toString())).andExpect(headerDoesNotExist("Cookie"))
            .andExpect(headerDoesNotExist("Authorization")).andExpect(header("If-None-Match","\"tag\""))
            .andRespond(withSuccess("{\"hits\":[]}",MediaType.APPLICATION_JSON));
        assertThat(service.search(user,project,"유리 & +?",new Conditions(null,null,"\"tag\"")).hits()).isEmpty();
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{}","null","{\"projects\":[null]}","{\"projects\":[{}]}","{\"projects\":[]} {}","{\"projects\":false}"})
    void rejectsMalformedOrIncompleteSuccess(String payload) {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/projects")).andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->new ContentApiClient(builder.build()).listProjects(UUID.randomUUID(),null))
            .isInstanceOf(com.loresentry.gateway.client.content.ContentCallFailure.class)
            .hasMessage("UPSTREAM_INVALID_RESPONSE");
        mocks.getServer().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"400,INVALID_REQUEST,NONE,INVALID_REQUEST", "409,DOCUMENT_LOCKED,NONE,DOCUMENT_LOCKED",
        "404,FILE_NOT_FOUND,NONE,FILE_NOT_FOUND", "401,USER_CONTEXT_REQUIRED,RELOGIN,UPSTREAM_INVALID_RESPONSE",
        "400,FILE_NOT_FOUND,NONE,UPSTREAM_INVALID_RESPONSE", "409,DOCUMENT_LOCKED,RELOGIN,UPSTREAM_INVALID_RESPONSE",
        "500,SQL_ERROR,NONE,UPSTREAM_INVALID_RESPONSE", "409,DOCUMENT_CONFLICT,NONE,UPSTREAM_INVALID_RESPONSE",
        "404,REFRESH_NOT_FOUND,NONE,REFRESH_NOT_FOUND", "404,REFRESH_DRAFT_NOT_FOUND,NONE,REFRESH_DRAFT_NOT_FOUND",
        "409,DOCUMENT_REFRESH_LOCKED,NONE,DOCUMENT_REFRESH_LOCKED", "409,REFRESH_REVIEW_PENDING,NONE,REFRESH_REVIEW_PENDING",
        "409,REFRESH_NOT_READY,NONE,REFRESH_NOT_READY", "409,REFRESH_IN_PROGRESS,RETRY_LATER,REFRESH_IN_PROGRESS",
        "503,REFRESH_UNAVAILABLE,RETRY_LATER,REFRESH_UNAVAILABLE", "409,REFRESH_IN_PROGRESS,NONE,UPSTREAM_INVALID_RESPONSE",
        "503,REFRESH_UNAVAILABLE,NONE,UPSTREAM_INVALID_RESPONSE", "409,REFRESH_NOT_READY,RETRY_LATER,UPSTREAM_INVALID_RESPONSE",
        "404,REFRESH_IN_PROGRESS,RETRY_LATER,UPSTREAM_INVALID_RESPONSE", "409,REFRESH_DRAFT_CONFLICT,NONE,UPSTREAM_INVALID_RESPONSE",
        "409,REFRESH_DRAFTS_UNMATCHED,NONE,UPSTREAM_INVALID_RESPONSE", "409,REFRESH_DOCUMENTS_CHANGED,NONE,UPSTREAM_INVALID_RESPONSE",
        "409,REFRESH_DRAFTS_INVALID,NONE,UPSTREAM_INVALID_RESPONSE"})
    void validatesErrorContract(int status,String code,String action,String result) {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/projects")).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\""+code+"\",\"message\":\"private SQL detail\",\"next_action\":\""+action+"\"}"));
        assertThatThrownBy(()->new ContentApiClient(builder.build()).listProjects(UUID.randomUUID(),null))
            .isInstanceOf(com.loresentry.gateway.client.content.ContentCallFailure.class).hasMessage(result);
        mocks.getServer().verify();
    }
    @Test void ignoresUncontractedSensitiveResponseFields() {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/projects"))
            .andRespond(withSuccess("{\"projects\":[],\"refresh_token\":\"secret\"}",MediaType.APPLICATION_JSON));
        var dto=com.loresentry.gateway.web.content.ContentDtos.Projects.from(new ContentApiClient(builder.build()).listProjects(UUID.randomUUID(),null));
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(dto)).isEqualTo("{\"projects\":[]}");
    }
    @Test void sampleProjectIsBodilessPostWithOnlyTheVerifiedUser() {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        var user=UUID.randomUUID();var project=UUID.randomUUID();
        mocks.getServer().expect(requestTo("http://content.test/projects/sample")).andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-User-Id",user.toString())).andExpect(headerDoesNotExist("Content-Type")).andExpect(content().string(""))
            .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\""+project+"\",\"name\":\"유리 정원의 기록\",\"description\":\"\",\"last_worked_at\":\"2026-09-30T00:00:00Z\",\"trashed_at\":null,\"created_at\":\"2026-09-30T00:00:00Z\",\"last_file\":null}"));
        var created=new ContentService(new ContentApiClient(builder.build()), null).createSampleProject(user,null,new Conditions(null,null,null));
        assertThat(created.id()).isEqualTo(project);assertThat(created.name()).isEqualTo("유리 정원의 기록");
        mocks.getServer().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(nullValues="NULL",value={"en,http://content.test/projects/sample?locale=en","ko,http://content.test/projects/sample?locale=ko",
        "NULL,http://content.test/projects/sample","'',http://content.test/projects/sample","fr,http://content.test/projects/sample",
        "EN,http://content.test/projects/sample","'en&locale=ko',http://content.test/projects/sample"})
    void sampleProjectForwardsOnlySupportedLocale(String locale,String upstream) {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        var user=UUID.randomUUID();var project=UUID.randomUUID();
        mocks.getServer().expect(requestTo(upstream)).andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-User-Id",user.toString())).andExpect(content().string(""))
            .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\""+project+"\",\"name\":\"The Glass Garden Records\",\"description\":\"\",\"last_worked_at\":\"2026-09-30T00:00:00Z\",\"trashed_at\":null,\"created_at\":\"2026-09-30T00:00:00Z\",\"last_file\":null}"));
        assertThat(new ContentService(new ContentApiClient(builder.build()), null).createSampleProject(user,locale,new Conditions(null,null,null)).id()).isEqualTo(project);
        mocks.getServer().verify();
    }
    @Test void sampleProjectRequiresCreatedStatus() {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/projects/sample"))
            .andRespond(withSuccess("{\"id\":\""+UUID.randomUUID()+"\",\"name\":\"n\",\"description\":\"\",\"last_worked_at\":\"2026-09-30T00:00:00Z\",\"created_at\":\"2026-09-30T00:00:00Z\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->new ContentApiClient(builder.build()).createSampleProject(UUID.randomUUID(),null,null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        mocks.getServer().verify();
    }
    @Test void feedbackPostsTheContractBodyWithTheVerifiedUser() {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        var user=UUID.randomUUID();var id=UUID.randomUUID();
        mocks.getServer().expect(requestTo("http://content.test/feedback")).andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-User-Id",user.toString())).andExpect(headerDoesNotExist("Cookie"))
            .andExpect(content().json("{\"category\":\"BUG\",\"message\":\"m\",\"page\":\"/projects/\",\"client\":null}",
                org.springframework.test.json.JsonCompareMode.STRICT))
            .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\""+id+"\",\"created_at\":\"2026-10-02T00:00:00Z\"}"));
        var created=new ContentService(new ContentApiClient(builder.build()), null).createFeedback(user,
            new com.loresentry.gateway.client.content.ContentData.FeedbackInput("BUG","m","/projects/",null),new Conditions(null,null,null));
        assertThat(created.id()).isEqualTo(id);
        mocks.getServer().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"400,INVALID_FEEDBACK,NONE,INVALID_FEEDBACK", "429,FEEDBACK_RATE_LIMITED,RETRY_LATER,FEEDBACK_RATE_LIMITED",
        "429,FEEDBACK_RATE_LIMITED,NONE,UPSTREAM_INVALID_RESPONSE", "400,INVALID_FEEDBACK,RETRY_LATER,UPSTREAM_INVALID_RESPONSE",
        "429,INVALID_FEEDBACK,NONE,UPSTREAM_INVALID_RESPONSE"})
    void feedbackErrorsPassThroughOnlyWhenContracted(int status,String code,String action,String result) {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/feedback")).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\""+code+"\",\"message\":\"m\",\"next_action\":\""+action+"\"}"));
        assertThatThrownBy(()->new ContentApiClient(builder.build()).createFeedback(UUID.randomUUID(),
            new com.loresentry.gateway.client.content.ContentData.FeedbackInput("BUG","m",null,null),null)).hasMessage(result);
        mocks.getServer().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{}","{\"id\":\"00000000-0000-0000-0000-000000000001\"}","{\"created_at\":\"2026-10-02T00:00:00Z\"}"})
    void feedbackRequiresIdAndCreatedAt(String payload) {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        mocks.getServer().expect(requestTo("http://content.test/feedback"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(payload));
        assertThatThrownBy(()->new ContentApiClient(builder.build()).createFeedback(UUID.randomUUID(),
            new com.loresentry.gateway.client.content.ContentData.FeedbackInput("BUG","m",null,null),null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        mocks.getServer().verify();
    }

    // ── 그래프 최신화 ──
    static final UUID PROJECT=UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");
    static final UUID REQUEST=UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62");
    static final UUID DRAFT=UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d63");
    static final UUID FILE=UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64");
    static final String REFRESH="http://content.test/projects/"+PROJECT+"/graph-refresh";
    /** 작업본 한 쪽. 관계 대상이 기존 문서가 아니라 이번 최신화의 새 문서 제안이다. */
    static final String SIDE="""
        {"title":"리아","body":{"schema_version":1,"doc":{"type":"doc","content":[]}},"legacy_body_md":null,
         "properties":[{"key":"description","value":"주인공"}],
         "relations":[{"relation_key":"related_place","target_document_id":null,"target_candidate_id":"new:PLACE:유리 정원","description":""}]}""";
    /** EDIT 작업본 하나와 left·right 가 빈 NEW 작업본 하나. */
    static final String DETAIL="""
        {"request_id":"%s","status":"READY","started_at":"2026-10-20T00:00:00Z","ready_at":"2026-10-20T00:01:00Z","completed_at":null,
         "error":null,"draft_counts":{"total":2,"matched":0,"new":1},"drafts":[
          {"id":"%s","kind":"EDIT","draft_revision":1,"matched":false,"updated_at":"2026-10-20T00:01:00Z",
           "document":{"id":"%s","title":"리아","folder_code":"CHARACTER","revision_no":4},"candidate":null,
           "base_version_id":"%s","base_revision_no":4,
           "base_snapshot":{"title":"리아","body":{"schema_version":1,"doc":{"type":"doc","content":[]}},"legacy_body_md":null,"properties":[],"relations":[]},
           "base_mismatch":false,"left_revision_no":4,"left_snapshot":%s,"right_snapshot":%s},
          {"id":"%s","kind":"NEW","draft_revision":0,"matched":false,"updated_at":"2026-10-20T00:01:00Z","document":null,
           "candidate":{"candidate_id":"new:PLACE:유리 정원","folder_code":"PLACE","name":"유리 정원"},
           "base_version_id":null,"base_revision_no":null,"base_snapshot":null,"base_mismatch":false,
           "left_revision_no":null,"left_snapshot":null,"right_snapshot":null}]}""".formatted(REQUEST,DRAFT,FILE,UUID.randomUUID(),SIDE,SIDE,UUID.randomUUID());

    /** 하나의 요청을 기대하는 서버와 그 서버를 부르는 client. */
    record Fixture(org.springframework.test.web.client.MockRestServiceServer server, ContentApiClient client) {}
    static Fixture fixture() {
        var builder=RestClient.builder().baseUrl("http://content.test");
        var mocks=new MockServerRestClientCustomizer();mocks.customize(builder);
        return new Fixture(mocks.getServer(),new ContentApiClient(builder.build()));
    }
    static org.springframework.test.web.client.ResponseCreator json(int status,String body) {
        return withStatus(org.springframework.http.HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter='|',value={
        "202|{\"request_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62\",\"status\":\"GENERATING\",\"files\":[{\"file_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64\",\"revision_no\":3}]}|GENERATING|1",
        "200|{\"request_id\":null,\"status\":\"NO_CHANGES\",\"files\":[]}|NO_CHANGES|0"})
    void refreshStartIsBodilessAndAcceptsBothSuccessStatuses(int status,String payload,String result,int files) {
        var f=fixture();var user=UUID.randomUUID();
        f.server().expect(requestTo(REFRESH)).andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-User-Id",user.toString())).andExpect(headerDoesNotExist("Content-Type")).andExpect(content().string(""))
            .andRespond(json(status,payload));
        var started=new ContentService(f.client(),null).startRefresh(user,PROJECT,new Conditions(null,null,null));
        assertThat(started.status()).isEqualTo(result);assertThat(started.files()).hasSize(files);
        f.server().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter='|',value={
        // 성공 상태가 아니다.
        "201|{\"request_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62\",\"status\":\"GENERATING\",\"files\":[]}",
        // 보냈는데 요청 id 가 없다.
        "202|{\"request_id\":null,\"status\":\"GENERATING\",\"files\":[]}",
        // 요약의 철자(NO_CHANGE)는 요청 결과의 값이 아니다.
        "200|{\"request_id\":null,\"status\":\"NO_CHANGE\",\"files\":[]}",
        "200|{\"request_id\":null,\"status\":\"NO_CHANGES\",\"files\":null}",
        "202|{\"request_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d62\",\"status\":\"GENERATING\",\"files\":[{\"file_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64\"}]}"})
    void refreshStartRejectsIncompleteSuccess(int status,String payload) {
        var f=fixture();
        f.server().expect(requestTo(REFRESH)).andRespond(json(status,payload));
        assertThatThrownBy(()->f.client().startRefresh(UUID.randomUUID(),PROJECT,null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
        f.server().verify();
    }
    @Test void refreshSummaryKeepsNullsAndFailureCause() {
        var f=fixture();
        f.server().expect(requestTo(REFRESH)).andExpect(method(org.springframework.http.HttpMethod.GET)).andRespond(json(200,
            "{\"request_id\":\""+REQUEST+"\",\"status\":\"FAILED\",\"started_at\":\"2026-10-20T00:00:00Z\",\"ready_at\":null,"
            +"\"completed_at\":\"2026-10-20T00:30:00Z\",\"error\":{\"code\":\"TIMEOUT\",\"message\":null},\"draft_counts\":{\"total\":0,\"matched\":0,\"new\":0}}"));
        var summary=f.client().latestRefresh(UUID.randomUUID(),PROJECT,null);
        assertThat(summary.error().code()).isEqualTo("TIMEOUT");assertThat(summary.error().message()).isNull();
        assertThat(summary.readyAt()).isNull();assertThat(summary.draftCounts().created()).isZero();
        f.server().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"NO_CHANGES","CAPTURING_BASE","ready"})
    void refreshSummaryRejectsUnknownStatus(String status) {
        var f=fixture();
        f.server().expect(requestTo(REFRESH)).andRespond(json(200,"{\"request_id\":\""+REQUEST+"\",\"status\":\""+status+"\",\"started_at\":\"2026-10-20T00:00:00Z\","
            +"\"ready_at\":null,\"completed_at\":null,\"error\":null,\"draft_counts\":{\"total\":0,\"matched\":0,\"new\":0}}"));
        assertThatThrownBy(()->f.client().latestRefresh(UUID.randomUUID(),PROJECT,null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
    }
    @Test void refreshDetailReadsEditAndNewDrafts() {
        var f=fixture();
        f.server().expect(requestTo(REFRESH+"/"+REQUEST)).andExpect(method(org.springframework.http.HttpMethod.GET)).andRespond(json(200,DETAIL));
        var detail=f.client().getRefresh(UUID.randomUUID(),PROJECT,REQUEST,null);
        var edit=detail.drafts().get(0);var created=detail.drafts().get(1);
        assertThat(edit.document().revisionNo()).isEqualTo(4L);assertThat(edit.baseSnapshot().body().get("schema_version").asInt()).isEqualTo(1);
        assertThat(edit.rightSnapshot().relations().get(0).targetCandidateId()).isEqualTo("new:PLACE:유리 정원");
        assertThat(edit.rightSnapshot().relations().get(0).targetDocumentId()).isNull();
        assertThat(created.candidate().name()).isEqualTo("유리 정원");assertThat(created.leftSnapshot()).isNull();
        assertThat(detail.draftCounts().created()).isEqualTo(1L);
        f.server().verify();
    }
    /** 올바른 상세 응답에서 한 군데만 바꿔 필수 값·모양 검사를 확인한다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter='|',value={
        "\"kind\":\"EDIT\"|\"kind\":\"MERGE\"",
        "\"document\":{\"id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64\",\"title\":\"리아\",\"folder_code\":\"CHARACTER\",\"revision_no\":4}|\"document\":null",
        "\"candidate\":{\"candidate_id\":\"new:PLACE:유리 정원\",\"folder_code\":\"PLACE\",\"name\":\"유리 정원\"}|\"candidate\":null",
        "\"target_document_id\":null,\"target_candidate_id\"|\"target_document_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d64\",\"target_candidate_id\"",
        "\"target_candidate_id\":\"new:PLACE:유리 정원\"|\"target_candidate_id\":null",
        "\"status\":\"READY\"|\"status\":\"DONE\"",
        "\"draft_counts\":{\"total\":2,\"matched\":0,\"new\":1}|\"draft_counts\":null",
        "\"new\":1|\"new\":1.5",
        "\"matched\":false,\"updated_at\"|\"updated_at\"",
        "\"title\":\"리아\",\"body\"|\"title\":null,\"body\"",
        "\"base_mismatch\":false|\"base_mismatch\":\"false\""})
    void refreshDetailRejectsIncompleteDrafts(String from,String to) {
        assertThat(DETAIL).contains(from);
        var f=fixture();
        f.server().expect(requestTo(REFRESH+"/"+REQUEST)).andRespond(json(200,DETAIL.replace(from,to)));
        assertThatThrownBy(()->f.client().getRefresh(UUID.randomUUID(),PROJECT,REQUEST,null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
    }
    @Test void draftSaveSendsBothSidesIncludingNulls() {
        var f=fixture();var user=UUID.randomUUID();
        var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[]}}");
        var right=new ContentData.DraftSnapshot("유리 정원",body,null,java.util.List.of(),
            java.util.List.of(new ContentData.DraftRelation(null,FILE,null,"처음 만난 곳")));
        f.server().expect(requestTo(REFRESH+"/"+REQUEST+"/drafts/"+DRAFT)).andExpect(method(org.springframework.http.HttpMethod.PATCH))
            .andExpect(header("X-User-Id",user.toString()))
            .andExpect(content().json("{\"draft_revision\":3,\"left_snapshot\":null,\"right_snapshot\":{\"title\":\"유리 정원\","
                +"\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[]}},\"legacy_body_md\":null,\"properties\":[],"
                +"\"relations\":[{\"relation_key\":null,\"target_document_id\":\""+FILE+"\",\"target_candidate_id\":null,\"description\":\"처음 만난 곳\"}]}}",
                org.springframework.test.json.JsonCompareMode.STRICT))
            .andRespond(json(200,"{\"id\":\""+DRAFT+"\",\"draft_revision\":4,\"matched\":false,\"updated_at\":\"2026-10-20T00:02:00Z\","
                +"\"left_snapshot\":null,\"right_snapshot\":"+SIDE+"}"));
        var saved=f.client().saveRefreshDraft(user,PROJECT,REQUEST,DRAFT,new ContentData.DraftSave(3L,null,right),null);
        assertThat(saved.draftRevision()).isEqualTo(4L);assertThat(saved.leftSnapshot()).isNull();
        f.server().verify();
    }
    @Test void confirmReadsWhatEachDraftBecame() {
        var f=fixture();
        f.server().expect(requestTo(REFRESH+"/"+REQUEST+"/confirm")).andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(content().string("")).andRespond(json(200,"{\"request_id\":\""+REQUEST+"\",\"status\":\"APPLIED\","
                +"\"created\":[{\"draft_id\":\""+DRAFT+"\",\"candidate_id\":\"new:PLACE:유리 정원\",\"file_id\":\""+FILE+"\"}],"
                +"\"updated\":[{\"draft_id\":\""+DRAFT+"\",\"file_id\":\""+FILE+"\",\"revision_no\":5}],\"unchanged\":[\""+DRAFT+"\"]}"));
        var confirmed=f.client().confirmRefresh(UUID.randomUUID(),PROJECT,REQUEST,null);
        assertThat(confirmed.created().get(0).fileId()).isEqualTo(FILE);assertThat(confirmed.updated().get(0).revisionNo()).isEqualTo(5L);
        assertThat(confirmed.unchanged()).containsExactly(DRAFT);
        f.server().verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"\"status\":\"READY\",\"created\":[],\"updated\":[],\"unchanged\":[]",
        "\"status\":\"APPLIED\",\"created\":[],\"updated\":[],\"unchanged\":[null]",
        "\"status\":\"APPLIED\",\"created\":[{\"draft_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d63\"}],\"updated\":[],\"unchanged\":[]"})
    void confirmRejectsIncompleteResult(String fields) {
        var f=fixture();
        f.server().expect(requestTo(REFRESH+"/"+REQUEST+"/confirm")).andRespond(json(200,"{\"request_id\":\""+REQUEST+"\","+fields+"}"));
        assertThatThrownBy(()->f.client().confirmRefresh(UUID.randomUUID(),PROJECT,REQUEST,null)).hasMessage("UPSTREAM_INVALID_RESPONSE");
    }

    static ContentCallFailure refreshFailure(String code,String details) {
        var f=fixture();
        f.server().expect(requestTo(REFRESH+"/"+REQUEST+"/confirm")).andRespond(json(409,
            "{\"code\":\""+code+"\",\"message\":\"private detail\",\"next_action\":\"NONE\","+details+"}"));
        return catchThrowableOfType(ContentCallFailure.class,()->f.client().confirmRefresh(UUID.randomUUID(),PROJECT,REQUEST,null));
    }
    @Test void draftConflictCarriesTheCurrentDraft() {
        var failure=refreshFailure("REFRESH_DRAFT_CONFLICT","\"current\":{\"draft_revision\":5,\"matched\":true,\"left_snapshot\":"+SIDE+",\"right_snapshot\":null}");
        assertThat(failure.status()).isEqualTo(409);assertThat(failure.code()).isEqualTo("REFRESH_DRAFT_CONFLICT");
        assertThat(failure.details().current().draftRevision()).isEqualTo(5L);
        assertThat(failure.details().current().leftSnapshot().title()).isEqualTo("리아");
        assertThat(failure.details().current().rightSnapshot()).isNull();
        assertThat(failure.current()).isNull();
    }
    @Test void confirmFailuresCarryTheirLists() {
        var unmatched=refreshFailure("REFRESH_DRAFTS_UNMATCHED","\"drafts\":[\""+DRAFT+"\"]");
        assertThat(unmatched.details().drafts()).containsExactly(DRAFT);
        var changed=refreshFailure("REFRESH_DOCUMENTS_CHANGED","\"documents\":[{\"draft_id\":\""+DRAFT+"\",\"file_id\":\""+FILE+"\"}]");
        assertThat(changed.details().documents().get(0).fileId()).isEqualTo(FILE);
        var invalid=refreshFailure("REFRESH_DRAFTS_INVALID","\"problems\":[{\"code\":\"RELATION_CONFLICT\",\"one\":\""+FILE+"\",\"other\":\"new:PLACE:유리 정원\"},"
            +"{\"code\":\"BODY_REQUIRED\",\"draft_id\":\""+DRAFT+"\"}]");
        assertThat(invalid.details().problems()).containsExactly(
            java.util.Map.of("code","RELATION_CONFLICT","one",FILE.toString(),"other","new:PLACE:유리 정원"),
            java.util.Map.of("code","BODY_REQUIRED","draft_id",DRAFT.toString()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter='|',value={
        "REFRESH_DRAFT_CONFLICT|\"current\":{\"matched\":true,\"left_snapshot\":null,\"right_snapshot\":null}",
        "REFRESH_DRAFT_CONFLICT|\"current\":\"stale\"",
        "REFRESH_DRAFTS_UNMATCHED|\"drafts\":[\"not-a-uuid\"]",
        "REFRESH_DRAFTS_UNMATCHED|\"drafts\":[null]",
        "REFRESH_DOCUMENTS_CHANGED|\"documents\":[{\"draft_id\":\"0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d63\"}]",
        "REFRESH_DRAFTS_INVALID|\"problems\":[{\"target\":\"new:PLACE:x\"}]",
        "REFRESH_DRAFTS_INVALID|\"problems\":[{\"code\":\"RELATION_CONFLICT\",\"one\":1}]",
        "REFRESH_DRAFTS_INVALID|\"problems\":[null]"})
    void malformedRefreshDetailsAreInvalidResponses(String code,String details) {
        assertThat(refreshFailure(code,details).code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
    }
    /** current 를 code 를 본 뒤 변환하도록 바꿨으므로 문서 충돌도 그대로 읽히는지 확인한다. */
    @Test void documentConflictStillCarriesCurrentAndBase() {
        var f=fixture();var doc=UUID.randomUUID();
        f.server().expect(requestTo("http://content.test/files/"+doc+"/content")).andRespond(json(409,
            "{\"code\":\"DOCUMENT_CONFLICT\",\"message\":\"m\",\"next_action\":\"NONE\",\"current\":{\"id\":\""+doc+"\",\"project_id\":\""+PROJECT+"\","
            +"\"title\":\"리아\",\"folder_code\":\"CHARACTER\",\"episode_id\":null,\"body\":{\"schema_version\":1,\"doc\":{}},\"legacy_body_md\":null,"
            +"\"properties\":[],\"relations\":[],\"locked\":false,\"char_count\":0,\"revision_no\":9,\"updated_at\":\"2026-10-20T00:00:00Z\"},\"base\":null}"));
        var failure=catchThrowableOfType(ContentCallFailure.class,()->f.client().saveContent(UUID.randomUUID(),doc,
            new ContentData.Snapshot("리아",null,"",java.util.List.of(),java.util.List.of()),null));
        assertThat(failure.code()).isEqualTo("DOCUMENT_CONFLICT");assertThat(failure.current().revisionNo()).isEqualTo(9L);
        assertThat(failure.base()).isNull();assertThat(failure.details()).isNull();
    }
}
