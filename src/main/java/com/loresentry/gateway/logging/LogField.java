package com.loresentry.gateway.logging;

import java.time.Duration;
import java.util.UUID;

/**
 * 로그 한 줄에 붙일 수 있는 필드. 이름은 LOG_SCHEMA.md 5절의 ECS 필드와 {@code loresentry.*} 필드만 쓴다.
 *
 * <p>생성자를 막고 정적 팩토리만 열어 두었다. 그래서 서비스 코드가 임의의 이름이나 값(이메일, 표시 이름, 원고, 요청 본문)을
 * 필드로 넣을 수 없다. 새 필드가 필요하면 LOG_SCHEMA.md 표에 먼저 추가한 뒤 여기에 팩토리를 만든다.
 *
 * <p>값이 null인 필드는 줄에서 빠진다.
 */
public final class LogField {
    private final String name;
    private final Object value;

    private LogField(String name, Object value) {
        this.name = name;
        this.value = value;
    }

    String name() {
        return name;
    }

    Object value() {
        return value;
    }

    private static LogField of(String name, Object value) {
        return value == null ? null : new LogField(name, value);
    }

    private static LogField uuid(String name, UUID value) {
        return of(name, value == null ? null : value.toString());
    }

    // ---------------------------------------------------------------- 도메인 ID (loresentry.*, keyword)

    public static LogField projectId(UUID id) {
        return uuid("loresentry.project_id", id);
    }

    public static LogField documentId(UUID id) {
        return uuid("loresentry.document_id", id);
    }

    public static LogField fileId(UUID id) {
        return uuid("loresentry.file_id", id);
    }

    /** ai-chat 대화 세션. 인증 세션과 헷갈리지 않게 chat 아래에 둔다. */
    public static LogField chatSessionId(UUID id) {
        return uuid("loresentry.chat.session_id", id);
    }

    public static LogField chatTurnId(UUID id) {
        return uuid("loresentry.chat.turn_id", id);
    }

    /** 관계도 최신화 요청(content의 graph-refresh requestId). */
    public static LogField refreshRequestId(UUID id) {
        return uuid("loresentry.refresh.request_id", id);
    }

    public static LogField refreshRunId(UUID id) {
        return uuid("loresentry.refresh.run_id", id);
    }

    /**
     * 문서 저장의 중복 방지 키(브라우저가 보내는 X-Save-Id). 같은 저장을 다시 보냈는지 이 값으로 찾는다.
     * 브라우저가 보낸 문자열이라 UUID 모양일 때만 남긴다.
     */
    public static LogField saveId(String header) {
        if (header == null) return null;
        try {
            return uuid("loresentry.save_id", UUID.fromString(header));
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    /** 회차 폴더(content episode_folders). */
    public static LogField episodeId(UUID id) {
        return uuid("loresentry.episode_id", id);
    }

    public static LogField memoId(UUID id) {
        return uuid("loresentry.memo_id", id);
    }

    /** 문서의 저장 버전. */
    public static LogField versionId(UUID id) {
        return uuid("loresentry.version_id", id);
    }

    /** 업로드한 이미지(S3 객체의 content 쪽 ID). */
    public static LogField imageId(UUID id) {
        return uuid("loresentry.image_id", id);
    }

    /** 관계도 최신화 결과를 사용자가 고치는 초안. */
    public static LogField refreshDraftId(UUID id) {
        return uuid("loresentry.refresh.draft_id", id);
    }

    public static LogField settingCheckRunId(UUID id) {
        return uuid("loresentry.setting_check.run_id", id);
    }

    public static LogField settingCheckFindingId(UUID id) {
        return uuid("loresentry.setting_check.finding_id", id);
    }

    // ---------------------------------------------------------------- HTTP (ECS)

    /** 요청 메서드(GET, POST 등). */
    public static LogField httpMethod(String method) {
        return of("http.request.method", method);
    }

    /** 쿼리를 뺀 경로. 쿼리에는 OAuth code·state, 검색어가 들어갈 수 있어 남기지 않는다. */
    public static LogField urlPath(String path) {
        return of("url.path", path);
    }

    /** 호출한 내부 서비스의 호스트(content-api 등). 들어온 요청이 아니라 나가는 호출에만 쓴다. */
    public static LogField urlDomain(String host) {
        return of("url.domain", host);
    }

    public static LogField statusCode(int status) {
        return of("http.response.status_code", (long) status);
    }

    /** ALB가 붙인 X-Amzn-Trace-Id. ALB 접근 로그의 trace_id와 같은 값이라 둘을 잇는다. */
    public static LogField requestId(String albTraceId) {
        return of("http.request.id", albTraceId);
    }

    // ---------------------------------------------------------------- 시간·에러 (ECS)

    /** 걸린 시간. ECS event.duration은 나노초다. */
    public static LogField duration(Duration duration) {
        return of("event.duration", duration == null ? null : duration.toNanos());
    }

    /** 예외 없이 실패 종류만 남길 때. 예외 클래스 이름이나 우리 오류 코드(UPSTREAM_UNAVAILABLE 등). */
    public static LogField errorType(String type) {
        return of("error.type", type);
    }

    /** 상대가 돌려준 오류 코드(예: upstream 응답 본문의 code). */
    public static LogField errorCode(String code) {
        return of("error.code", code);
    }
}
