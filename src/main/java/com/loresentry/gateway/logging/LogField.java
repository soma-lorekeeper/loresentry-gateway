package com.loresentry.gateway.logging;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 로그 한 줄에 붙일 수 있는 필드. 이름은 LOG_SCHEMA.md 5절의 ECS 필드와 {@code loresentry.*} 필드만 쓴다.
 *
 * <p>생성자를 막고 정적 팩토리만 열어 두었다. 그래서 서비스 코드가 임의의 이름이나 값(이메일, 표시 이름, 원고, 요청 본문)을
 * 필드로 넣을 수 없다. 새 필드가 필요하면 LOG_SCHEMA.md 표에 먼저 추가한 뒤 여기에 팩토리를 만든다.
 *
 * <p>필드 하나가 이름·값 쌍을 여러 개 가질 수 있다. 예를 들어 문자열 입력값은 {@code param.title: "[redacted]"}와
 * {@code param.title_length: 7} 두 쌍이 된다. 값이 null인 필드는 줄에서 빠진다.
 *
 * <p>입력값({@link #param})·결과({@link #result})는 LOG_SCHEMA.md 5절 "입력값과 결과" 규칙대로 값을 바꾼다.
 * <ul>
 *   <li>숫자·불리언·enum·UUID: 그대로</li>
 *   <li>문자열: 늘 {@code [redacted]}와 {@code <이름>_length}. 제목·본문·검색어 같은 내용이 남지 않는다</li>
 *   <li>허용 목록이 있는 코드 문자열({@link #code}): 목록 안이면 그대로, 밖이면 {@code other}</li>
 *   <li>컬렉션·Map: {@code <이름>_count}, JSON 본문·byte 배열: {@code <이름>_bytes}</li>
 *   <li>그 밖의 객체(DTO 등): 받는 메서드가 없어 컴파일되지 않는다. 필요한 숫자·코드만 골라 넘긴다</li>
 * </ul>
 * 이름은 파라미터 이름을 snake_case로 쓰고, 같은 이름은 늘 같은 타입으로 쓴다(ES는 처음 들어온 값으로 타입을 정한다).
 */
public final class LogField {
    /** 가린 문자열 자리에 남기는 값. Logstash mask.rb의 표시와 같다. */
    static final String REDACTED = "[redacted]";
    /** 허용 목록 밖의 코드 값을 대신하는 값. */
    static final String OTHER = "other";
    /** 안전한 스택 트레이스에 남기는 원인 수·원인마다의 프레임 수 상한. */
    private static final int MAX_CAUSES = 8;
    private static final int MAX_FRAMES = 30;
    private static final String PARAM = "loresentry.param.";
    private static final String RESULT = "loresentry.result.";

    private final List<Map.Entry<String, Object>> entries;

    private LogField(List<Map.Entry<String, Object>> entries) {
        this.entries = entries;
    }

    /** 이 필드가 줄에 더하는 이름·값 쌍(점 이름). AppLogger가 key-value로 넘긴다. */
    List<Map.Entry<String, Object>> entries() {
        return entries;
    }

    private static LogField of(String name, Object value) {
        return value == null ? null : new LogField(List.of(Map.entry(name, value)));
    }

    private static LogField uuid(String name, UUID value) {
        return of(name, value == null ? null : value.toString());
    }

    // ---------------------------------------------------------------- 도메인 ID (loresentry.*, keyword)
    // 이 줄의 대상·대상이 속한 범위·새로 만든 대상만 여기에 넣는다. 참조만 하는 ID는 param("<역할>_<종류>_id", id)로 넣는다.

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

    /** 관계도 최신화 요청(content의 graph-refresh requestId). content의 run(refresh_runs.id)도 같은 값이라 이 필드 하나만 쓴다. */
    public static LogField refreshRequestId(UUID id) {
        return uuid("loresentry.refresh.request_id", id);
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

    /**
     * content 호출의 조건부 요청 값(If-Match·X-Save-Id·If-None-Match). If-Match·If-None-Match는 ETag 문자열이라 가려 길이만 남기고
     * ({@code param.if_match}, {@code param.if_none_match}), X-Save-Id는 문서 저장의 중복 방지 키라 전용 필드({@link #saveId})다.
     * 없는 값은 줄에서 빠지고, 셋 다 없으면 null이다. client 타입(Conditions)을 받지 않고 값만 받는다.
     */
    public static LogField conditions(String ifMatch, String saveId, String ifNoneMatch) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>();
        for (LogField field : new LogField[] {param("if_match", ifMatch), saveId(saveId), param("if_none_match", ifNoneMatch)}) {
            if (field != null) entries.addAll(field.entries);
        }
        return entries.isEmpty() ? null : new LogField(List.copyOf(entries));
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

    // ---------------------------------------------------------------- HTTP (ECS·OpenTelemetry)

    /** 요청 메서드(GET, POST 등). */
    public static LogField httpMethod(String method) {
        return of("http.request.method", method);
    }

    /**
     * 브라우저가 부른 실제 경로(쿼리 제외). RequestLogFilter가 요청 하나의 모든 줄에 붙인다.
     * 쿼리에는 OAuth code·state, 검색어가 들어갈 수 있어 남기지 않는다.
     */
    public static LogField urlPath(String path) {
        return of("url.path", path);
    }

    /**
     * Spring이 매칭한 경로 템플릿(/projects/{projectId}). 엔드포인트별로 묶어 볼 때 쓴다. OpenTelemetry http.route와 같은 뜻이다.
     * RequestLogFilter가 요청 시작 때 찾아 요청 하나의 모든 줄에 붙인다. 맞는 경로가 없는 요청(404)에는 없다.
     */
    public static LogField route(String pattern) {
        return of("http.route", pattern);
    }

    /** 호출한 내부 서비스의 호스트(content-api 등). 들어온 요청이 아니라 나가는 호출에만 쓴다. */
    public static LogField urlDomain(String host) {
        return of("url.domain", host);
    }

    /**
     * 나가는 호출의 대상 주소(scheme://host:port/path). ECS·OpenTelemetry HTTP client의 {@code url.full}이다.
     * 같은 줄의 {@code url.path}는 브라우저가 부른 경로라서, 내부 서비스 쪽 경로는 이 필드로 본다.
     * 쿼리는 넣지 않는다(검색어 등이 들어갈 수 있다).
     */
    public static LogField urlFull(URI uri) {
        if (uri == null) return null;
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return of("url.full", uri.getScheme() + "://" + uri.getHost() + port + uri.getRawPath());
    }

    public static LogField statusCode(int status) {
        return of("http.response.status_code", (long) status);
    }

    /** ALB가 붙인 X-Amzn-Trace-Id. ALB 접근 로그의 trace_id와 같은 값이라 둘을 잇는다. */
    public static LogField requestId(String albTraceId) {
        return of("http.request.id", albTraceId);
    }

    // ---------------------------------------------------------------- 시간·에러 (ECS)

    /**
     * 걸린 시간. ECS event.duration은 나노초다. 시간은 로깅 팩토리(RequestLogFilter, UpstreamCallLogInterceptor, LogFlow)만 잰다.
     * 서비스 코드가 시간을 재서 넘기지 않도록 이 패키지 안에서만 보인다.
     */
    static LogField duration(Duration duration) {
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

    /**
     * 실패의 code 중 상대가 오류 본문으로 돌려준 것만 error.code로 남긴다. gateway가 스스로 붙인 code(gatewayCodes, 예:
     * UPSTREAM_INVALID_RESPONSE·CONTENT_UNAVAILABLE)는 상대가 보낸 값이 아니라 남기지 않는다(null).
     *
     * @param code         실패의 code
     * @param gatewayCodes gateway가 그 호출의 실패에 스스로 붙이는 code
     */
    public static LogField upstreamErrorCode(String code, Set<String> gatewayCodes) {
        return code == null || gatewayCodes.contains(code) ? null : errorCode(code);
    }

    /**
     * 상대(auth·content·ai-chat)가 돌려준 오류 본문을 계약에 맞지 않아 거절한 이유(error.type)와 그 code(error.code).
     * error.type은 필수 칸이 없으면 MISSING_FIELD, 우리가 아는 code인데 상태·next_action이 계약과 다르면 ERROR_CONTRACT_MISMATCH,
     * 모르는 code면 UNKNOWN_ERROR_CODE다. error.code는 우리가 아는 code일 때만 남긴다(모르는 code는 상대가 보낸 임의 문자열일 수 있다).
     *
     * @param code     오류 본문의 code. 없으면 null(MISSING_FIELD)
     * @param complete code 밖의 필수 칸(message 등)이 모두 있는지. 아니면 MISSING_FIELD
     * @param known    우리가 아는 code(오류 계약 목록 안)인지
     */
    public static LogField upstreamError(String code, boolean complete, boolean known) {
        String type = code == null || !complete ? "MISSING_FIELD" : known ? "ERROR_CONTRACT_MISMATCH" : "UNKNOWN_ERROR_CODE";
        if (!known || code == null) return of("error.type", type);
        return new LogField(List.of(Map.entry("error.type", type), Map.entry("error.code", code)));
    }

    /**
     * 예외 메시지를 뺀 스택 트레이스({@code error.stack_trace}). 원인마다 클래스 이름과 프레임만 적는다.
     * 예외 메시지에는 요청 값·주소·토큰이 섞일 수 있어서, 예외로 생긴 warn·error 줄의 원인을 남길 때 예외 대신 이 필드를 넘긴다.
     */
    public static LogField safeStackTrace(Throwable failure) {
        if (failure == null) return null;
        StringBuilder trace = new StringBuilder();
        int causes = 0;
        for (Throwable cause = failure; cause != null && causes < MAX_CAUSES; cause = cause.getCause(), causes++) {
            trace.append(cause.getClass().getName()).append('\n');
            Arrays.stream(cause.getStackTrace()).limit(MAX_FRAMES)
                    .forEach(frame -> trace.append("  at ").append(frame).append('\n'));
            // 원인이 자기 자신을 가리키는 예외는 거기서 멈춘다
            if (cause.getCause() == cause) break;
        }
        return of("error.stack_trace", trace.toString());
    }

    // ---------------------------------------------------------------- 입력값 (loresentry.param.*)

    public static LogField param(String name, Number value) {
        return of(PARAM + name, value);
    }

    public static LogField param(String name, Boolean value) {
        return of(PARAM + name, value);
    }

    public static LogField param(String name, Enum<?> value) {
        return of(PARAM + name, value == null ? null : value.name());
    }

    /** 참조만 하는 ID(이동할 위치, 원본, 연결 상대 등)는 역할을 붙인 이름으로: param("target_folder_id", id). */
    public static LogField param(String name, UUID value) {
        return uuid(PARAM + name, value);
    }

    /** 문자열은 내용을 남기지 않는다: [redacted]와 길이. */
    public static LogField param(String name, String value) {
        return masked(PARAM, name, value);
    }

    public static LogField param(String name, Collection<?> value) {
        return of(PARAM + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField param(String name, Map<?, ?> value) {
        return of(PARAM + name + "_count", value == null ? null : (long) value.size());
    }

    /** JSON 본문은 내용 대신 바이트 수만 남긴다. */
    public static LogField param(String name, JsonNode value) {
        return of(PARAM + name + "_bytes", value == null ? null : jsonBytes(value));
    }

    public static LogField param(String name, byte[] value) {
        return of(PARAM + name + "_bytes", value == null ? null : (long) value.length);
    }

    /**
     * 정해진 낱말 중 하나인 코드 문자열(verdict, kind, intent, locale 등). 허용 목록 안이면 그대로, 밖이면 other로 남긴다.
     * 사용자가 이 칸에 자유 텍스트를 보내도 그 값은 남지 않는다.
     */
    public static LogField code(String name, String value, Set<String> allowed) {
        return of(PARAM + name, value == null ? null : (allowed.contains(value) ? value : OTHER));
    }

    // ---------------------------------------------------------------- 처리 결과 (loresentry.result.*)

    public static LogField result(String name, Number value) {
        return of(RESULT + name, value);
    }

    public static LogField result(String name, Boolean value) {
        return of(RESULT + name, value);
    }

    public static LogField result(String name, Enum<?> value) {
        return of(RESULT + name, value == null ? null : value.name());
    }

    public static LogField result(String name, UUID value) {
        return uuid(RESULT + name, value);
    }

    public static LogField result(String name, String value) {
        return masked(RESULT, name, value);
    }

    public static LogField result(String name, Collection<?> value) {
        return of(RESULT + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField result(String name, Map<?, ?> value) {
        return of(RESULT + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField result(String name, JsonNode value) {
        return of(RESULT + name + "_bytes", value == null ? null : jsonBytes(value));
    }

    /** 결과 코드 문자열(상대가 돌려준 상태 이름 등). 허용 목록 밖이면 other. */
    public static LogField resultCode(String name, String value, Set<String> allowed) {
        return of(RESULT + name, value == null ? null : (allowed.contains(value) ? value : OTHER));
    }

    private static LogField masked(String prefix, String name, String value) {
        if (value == null) return null;
        return new LogField(List.of(Map.entry(prefix + name, REDACTED), Map.entry(prefix + name + "_length", (long) value.length())));
    }

    private static long jsonBytes(JsonNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
