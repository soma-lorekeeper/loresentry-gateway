package com.loresentry.gateway.logging;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * 흐름 하나의 시작·종료 줄을 남기는 로깅 팩토리(LOG_SCHEMA.md 5절 "만들기"). content의 같은 이름 클래스와 같은 규칙·API다
 * (gateway에는 스케줄 작업·Kafka 레코드가 없어 외부 호출과 별도 작업만 있다).
 *
 * <p>서비스 코드는 흐름의 재료(작업 이름, 호출 대상)와 할 일(람다)만 넘긴다. 걸린 시간을 재는 일, 결과로 레벨을 정하는 일,
 * 시작·종료 줄을 쓰는 일, 흐름의 span을 열고 지금 문맥으로 두는 일은 모두 여기서 한다. 할 일이 예외로 끝나도 종료 줄이 남고 span도
 * 같은 범위에서 닫힌다(실패면 span 상태가 ERROR다).
 *
 * <p>흐름 종류
 * <ul>
 *   <li>{@link #call}: RestClient를 거치지 않는 외부 호출 하나(채팅 SSE의 Apache HttpClient 호출, 세션 저장소 Valkey).
 *       {@code <target> call} span을 열고 그 안에서 호출하고 결과 줄 하나를 남긴다. 시작은 호출하는 client 메서드의 진입 줄이다.
 *       RestClient 호출은 UpstreamCallLogInterceptor가 같은 span과 줄을 남긴다</li>
 *   <li>{@link #task}: 요청 안·밖의 별도 작업(채팅 SSE 본문 relay, 기동 때 세션 저장소 예열). {@code <name>} span을 열고
 *       {@code <name> start}와 {@code <name> completed} 또는 {@code <name> failed}를 남긴다</li>
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
        // 호출을 감싸는 span을 먼저 열고 흐름 객체(걸린 시간을 재기 시작한다)를 만든다. 이유는 runCall에 있다
        Span span = TraceContext.tracer().spanBuilder(target + " call").startSpan();
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        if (uri != null) flow.result(LogField.urlDomain(uri.getHost()), LogField.urlFull(uri));
        return flow.runCall(span, target, body);
    }

    /**
     * 외부 호출 하나(세션 저장소 Valkey). 새 로그 객체를 만들지 않고 지금 흐름의 줄로 남는다. 호출을 감싸는 span({@code <target> call})을
     * 열고 그 안에서 할 일을 한다. 시작은 이 호출을 부른 client 메서드의 진입 줄이고, 끝나면 그 span 안에서 결과 줄 하나를 남긴다: 성공은
     * {@code <target> call completed}(걸린 시간, 응답 상태), 상대의 5xx는 같은 문구의 warn과 error.type, {@link #fail}이나 예외는
     * {@code <target> call failed}(warn, error.type). 예외는 그대로 다시 던진다.
     *
     * @param target 호출 대상(valkey 등). 문구에 쓴다
     * @return 호출이 돌려준 값
     */
    public static <T, E extends Exception> T call(String target, Call<T, E> body) throws E {
        // 호출을 감싸는 span을 먼저 열고 흐름 객체(걸린 시간을 재기 시작한다)를 만든다. 이유는 runCall에 있다
        Span span = TraceContext.tracer().spanBuilder(target + " call").startSpan();
        return new LogFlow(AppLogger.callerOf(LogFlow.class)).runCall(span, target, body);
    }

    /**
     * 요청 안·밖의 별도 작업(채팅 SSE 본문 relay, 기동 때 세션 저장소 예열). 새 로그 객체를 만들지 않고 지금 흐름의 줄로 남는다. 작업의
     * span({@code <name>})을 열고 그 안에서 시작 줄({@code <name> start}, 시작할 때 아는 값)을 남기고 할 일을 한 뒤 종료 줄
     * ({@code <name> completed}, 결과 값·걸린 시간)을 남긴다. {@link #fail}로 알린 실패는 {@code <name> failed}(warn), 예외는
     * {@code <name> failed}(error, 메시지 없는 스택)이고 예외는 그대로 다시 던진다. 실패면 span 상태도 ERROR다.
     *
     * @param name  작업 이름(문구와 span 이름에 쓴다. 예: "ai-chat stream relay")
     * @param start 시작할 때 아는 값
     */
    public static <E extends Exception> void task(String name, Body<E> body, LogField... start) throws E {
        // 작업 하나의 span(LOG_SCHEMA.md 7절 "별도 작업"). 시작·종료 줄과 작업 안의 호출 span이 이 span 아래에 남는다. 요청 안의 작업
        // (채팅 relay)은 요청 서버 span의 자식이고, 흐름 밖에서 시작한 작업(기동 때 세션 저장소 예열)이면 이 span이 root다.
        // 호출과 같이 span을 먼저 열고 걸린 시간을 재기 시작한다
        Span span = TraceContext.tracer().spanBuilder(name).startSpan();
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        try (Scope ignored = span.makeCurrent()) {
            AppLogger.current().infoAs(flow.origin, name + " start", start);
            try {
                body.run(flow);
            } catch (Throwable failure) {
                flow.end(Severity.ERROR, name + " failed", failure);
                TraceContext.failed(span, failure);
                throw failure;
            }
            if (flow.failureType != null) {
                flow.end(Severity.WARN, name + " failed", null);
                span.setStatus(StatusCode.ERROR, flow.failureType);
            } else {
                flow.end(Severity.INFO, name + " completed", null);
            }
        } finally {
            flow.closeBindings();
            span.end();
        }
    }

    // ---------------------------------------------------------------- 내부

    /**
     * {@link #call}의 몸통. 호출을 감싸는 span을 지금 문맥으로 두고 호출한 뒤, 그 span 안에서 결과 줄 하나를 남긴다.
     *
     * <p>span(LOG_SCHEMA.md 7절 "외부 호출"): 계측이 여는 CLIENT span(채팅 SSE의 Apache HttpClient 호출, Valkey의 Lettuce 명령)이 이 span의
     * 자식이 되고, 결과 줄을 이 span 안에서 찍어 결과 줄의 span.id가 이 span이 된다. 그래서 줄 하나로 APM의 그 호출을 바로 찾는다. 부르는
     * 쪽이 span을 먼저 열고 흐름 객체(걸린 시간을 재기 시작한다)를 만든다. 반대 순서면 처음 호출에서 tracer를 꺼내는 시간이 결과 줄의
     * event.duration에만 들어가 span 길이와 어긋난다. 실패(예외, {@link #fail}, 상대의 5xx)면 span 상태가 ERROR다.
     */
    private <T, E extends Exception> T runCall(Span span, String target, Call<T, E> body) throws E {
        try (Scope ignored = span.makeCurrent()) {
            T value;
            try {
                value = body.run(this);
            } catch (Throwable failure) {
                end(Severity.WARN, target + " call failed", failure);
                TraceContext.failed(span, failure);
                throw failure;
            }
            if (failureType != null) {
                end(Severity.WARN, target + " call failed", null);
                span.setStatus(StatusCode.ERROR, failureType);
            } else if (status != null && status >= 500) {
                failureType = UPSTREAM_SERVER_ERROR;
                end(Severity.WARN, target + " call completed", null);
                span.setStatus(StatusCode.ERROR, UPSTREAM_SERVER_ERROR);
            } else {
                end(Severity.INFO, target + " call completed", null);
            }
            return value;
        } finally {
            closeBindings();
            span.end();
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
