package com.loresentry.gateway.logging;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 흐름 하나의 시작·종료 줄을 남기는 로깅 팩토리(LOG_SCHEMA.md 5절 "만들기"). content의 같은 이름 클래스와 같은 규칙·API다
 * (gateway에는 스케줄 작업·Kafka 레코드가 없어 외부 호출과 별도 작업만 있다).
 *
 * <p>서비스 코드는 흐름의 재료(작업 이름, 호출 대상)와 할 일(람다)만 넘긴다. 걸린 시간을 재는 일, 결과로 레벨을 정하는 일,
 * 시작·종료 줄을 쓰는 일은 모두 여기서 한다. 할 일이 예외로 끝나도 종료 줄이 남는다.
 *
 * <p>흐름 종류
 * <ul>
 *   <li>{@link #call}: RestClient를 거치지 않는 외부 호출 하나(채팅 SSE의 Apache HttpClient 호출, 세션 저장소 Valkey).
 *       시작은 호출하는 client 메서드의 진입 줄이고, 결과 줄 하나를 남긴다. RestClient 호출은 UpstreamCallLogInterceptor가 남긴다</li>
 *   <li>{@link #task}: 요청 안·밖의 별도 작업(채팅 SSE 본문 relay, 기동 때 세션 저장소 예열). {@code <name> start}와
 *       {@code <name> completed} 또는 {@code <name> failed}</li>
 * </ul>
 *
 * <p>할 일(람다)은 흐름 객체를 받아 진행 중에 알게 된 값을 알린다: {@link #with}(이후 모든 줄에 붙일 대상 ID),
 * {@link #result}(종료 줄의 결과 값), {@link #status}(호출의 응답 상태), {@link #fail}(예외 없이 끝난 실패의 이유).
 * 흐름 중간에 알게 된 사용자는 {@code AppLogger.current().identify(userId)}로 알린다.
 *
 * <p>시작·종료 줄의 위치(log.logger·log.origin.function·log.layer)는 이 클래스가 아니라 흐름을 연 서비스·client 메서드다
 * (예: ChatApiClient.send의 client 레이어). 실패 줄에는 예외 메시지를 싣지 않는다. 클래스 이름(error.type)과 메시지 없는
 * 스택만 남긴다(예외 메시지에 주소·요청 값이 섞일 수 있다).
 */
public final class LogFlow {
    /** 흐름 안에서 할 일. 흐름 객체로 진행 중에 알게 된 값을 알린다. */
    @FunctionalInterface
    public interface Body<E extends Exception> {
        void run(LogFlow flow) throws E;
    }

    /** 값을 돌려주는 외부 호출. 흐름 객체로 응답 상태 등을 알린다. */
    @FunctionalInterface
    public interface Call<T, E extends Exception> {
        T run(LogFlow flow) throws E;
    }

    /** 상대가 5xx로 답한 호출의 error.type. 응답 상태 자체는 http.response.status_code에 있다. */
    static final String UPSTREAM_SERVER_ERROR = "UPSTREAM_SERVER_ERROR";

    /** 종료 줄의 레벨. */
    private enum Severity { INFO, WARN, ERROR }

    /** 흐름을 연 서비스·client 메서드의 프레임. 시작·종료 줄의 위치다. */
    private final StackWalker.StackFrame origin;
    /** 걸린 시간을 재는 시작점. 시각이 아니라 흘러간 시간만 재는 값이다. */
    private final long started = System.nanoTime();
    /** 종료 줄에 붙일 결과 값({@link #result}). 호출 대상 주소(url.domain·url.full)도 여기에 먼저 넣는다. */
    private final List<LogField> result = new ArrayList<>();
    /** {@link #with}가 연 바인딩. 흐름이 끝날 때 연 반대 순서로 닫는다. */
    private final Deque<AppLogger.Binding> bindings = new ArrayDeque<>();
    /** 외부 호출의 응답 상태. 모르면 null. */
    private Integer status;
    /** 예외 없이 끝난 실패의 이유({@link #fail}). 실패가 아니면 null. */
    private String failureType;

    private LogFlow(StackWalker.StackFrame origin) {
        this.origin = origin;
    }

    // ---------------------------------------------------------------- 진행 중에 알리는 값

    /**
     * 진행 중에 알게 된 대상 ID(예: 채팅 relay의 chat.session_id)를 이 흐름의 이후 모든 줄에 붙인다. 종료 줄에도 붙는다.
     */
    public LogFlow with(LogField... fields) {
        bindings.push(AppLogger.bind(AppLogger.current().with(fields)));
        return this;
    }

    /** 종료 줄에 붙일 결과 값(바이트 수, 단계 등). 여러 번 부르면 모두 붙는다. */
    public LogFlow result(LogField... fields) {
        for (LogField field : fields) if (field != null) result.add(field);
        return this;
    }

    /** 외부 호출의 응답 상태. 5xx면 결과 줄이 warn이 된다. */
    public LogFlow status(int status) {
        this.status = status;
        return this;
    }

    /** 예외 없이 끝난 실패를 알린다(예: 답이 끝나기 전에 끊긴 채팅 스트림). 종료 줄이 warn이 되고 이 값이 error.type이다. */
    public LogFlow fail(String errorType) {
        this.failureType = errorType;
        return this;
    }

    // ---------------------------------------------------------------- 흐름 종류

    /**
     * 주소를 아는 외부 호출 하나(채팅 SSE의 HTTP 호출). {@link #call(String, Call)}과 같고, 결과 줄에 대상 호스트({@code url.domain})와
     * 대상 주소({@code url.full}, 쿼리 제외)를 더한다. 그래서 RestClient 호출 결과 줄(UpstreamCallLogInterceptor)과 같은 필드로 찾는다.
     *
     * @param target 호출 대상(upstream, valkey 등). 문구에 쓴다
     * @param uri    호출 주소. 쿼리는 줄에 남지 않는다
     * @return 호출이 돌려준 값
     */
    public static <T, E extends Exception> T call(String target, URI uri, Call<T, E> body) throws E {
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        if (uri != null) flow.result(LogField.urlDomain(uri.getHost()), LogField.urlFull(uri));
        return flow.runCall(target, body);
    }

    /**
     * 외부 호출 하나(세션 저장소 Valkey). 새 로그 객체를 만들지 않고 지금 흐름의 줄로 남는다. 시작은 이 호출을 부른 client 메서드의
     * 진입 줄이고, 끝나면 결과 줄 하나를 남긴다: 성공은 {@code <target> call completed}(걸린 시간, 응답 상태), 상대의 5xx는 같은
     * 문구의 warn과 error.type, {@link #fail}이나 예외는 {@code <target> call failed}(warn, error.type). 예외는 그대로 다시 던진다.
     *
     * @param target 호출 대상(valkey 등). 문구에 쓴다
     * @return 호출이 돌려준 값
     */
    public static <T, E extends Exception> T call(String target, Call<T, E> body) throws E {
        return new LogFlow(AppLogger.callerOf(LogFlow.class)).runCall(target, body);
    }

    /**
     * 요청 안·밖의 별도 작업(채팅 SSE 본문 relay, 기동 때 세션 저장소 예열). 새 로그 객체를 만들지 않고 지금 흐름의 줄로 남는다. 시작 줄
     * ({@code <name> start}, 시작할 때 아는 값)을 남기고 할 일을 한 뒤 종료 줄({@code <name> completed}, 결과 값·걸린 시간)을
     * 남긴다. {@link #fail}로 알린 실패는 {@code <name> failed}(warn), 예외는 {@code <name> failed}(error, 메시지 없는 스택)이고
     * 예외는 그대로 다시 던진다.
     *
     * @param name  작업 이름(문구에 쓴다. 예: "ai-chat stream relay")
     * @param start 시작할 때 아는 값
     */
    public static <E extends Exception> void task(String name, Body<E> body, LogField... start) throws E {
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        try {
            AppLogger.current().infoAs(flow.origin, name + " start", start);
            try {
                body.run(flow);
            } catch (Throwable failure) {
                flow.end(Severity.ERROR, name + " failed", failure);
                throw failure;
            }
            if (flow.failureType != null) flow.end(Severity.WARN, name + " failed", null);
            else flow.end(Severity.INFO, name + " completed", null);
        } finally {
            flow.closeBindings();
        }
    }

    // ---------------------------------------------------------------- 내부

    /** {@link #call}의 몸통. 호출을 하고 결과 줄 하나를 남긴다. */
    private <T, E extends Exception> T runCall(String target, Call<T, E> body) throws E {
        try {
            T value;
            try {
                value = body.run(this);
            } catch (Throwable failure) {
                end(Severity.WARN, target + " call failed", failure);
                throw failure;
            }
            if (failureType != null) {
                end(Severity.WARN, target + " call failed", null);
            } else if (status != null && status >= 500) {
                failureType = UPSTREAM_SERVER_ERROR;
                end(Severity.WARN, target + " call completed", null);
            } else {
                end(Severity.INFO, target + " call completed", null);
            }
            return value;
        } finally {
            closeBindings();
        }
    }

    /**
     * 종료 줄 하나. 결과 값, 응답 상태, 실패 이유(예외면 클래스 이름, 아니면 {@link #fail}의 값), 예외의 메시지 없는 스택, 걸린 시간을
     * 붙인다. 지금 바인딩된 객체로 찍으므로 요청 문맥과 {@link #with}로 더한 ID가 함께 붙는다.
     */
    private void end(Severity severity, String message, Throwable failure) {
        List<LogField> fields = new ArrayList<>(result);
        if (status != null) fields.add(LogField.statusCode(status));
        fields.add(LogField.errorType(failure != null ? failure.getClass().getName() : failureType));
        fields.add(LogField.safeStackTrace(failure));
        fields.add(LogField.duration(Duration.ofNanos(System.nanoTime() - started)));
        LogField[] all = fields.toArray(LogField[]::new);
        AppLogger logger = AppLogger.current();
        switch (severity) {
            case INFO -> logger.infoAs(origin, message, all);
            case WARN -> logger.warnAs(origin, message, all);
            case ERROR -> logger.errorAs(origin, message, all);
        }
    }

    /** {@link #with}가 연 바인딩을 연 반대 순서로 닫는다. */
    private void closeBindings() {
        while (!bindings.isEmpty()) bindings.pop().close();
    }
}
