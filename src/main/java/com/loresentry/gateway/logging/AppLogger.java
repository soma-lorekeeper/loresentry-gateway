package com.loresentry.gateway.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * 서비스 코드가 로그를 남기는 유일한 통로다(LOG_SCHEMA.md 5절). SLF4J·System.out을 직접 쓰지 않고 이 객체의 메서드만 부른다.
 *
 * <p>흐름
 * <ol>
 *   <li>요청·flow 시작점({@link RequestLogFilter} 등)이 그때 아는 값(traceId, spanId, userId)을 생성자 인자로 넘겨 만든다.</li>
 *   <li>{@link #bind(AppLogger)}로 요청 범위 저장소(ThreadLocal)에 넣는다. 같은 요청의 어느 계층이든 {@link #current()}로 꺼낸다.</li>
 *   <li>레벨은 info·warn·error만 있다. 필드는 {@link LogField}의 정해 둔 이름만 받으므로 이메일·본문 같은 값이 들어갈 자리가 없다.</li>
 * </ol>
 *
 * <p>줄마다 이 객체의 trace·span·user 값을 MDC에 실어 SLF4J로 넘기고, {@link EcsLogFormatter}가 ECS JSON 한 줄로 만든다.
 * 그래서 다른 스레드로 넘겨받은 객체로 찍어도 원래 요청의 값이 남는다. 호출한 클래스·메서드는 StackWalker로 찾아
 * {@code log.logger}·{@code log.origin.function}에 넣는다.
 *
 * <p>객체는 바꿀 수 없다. 사용자처럼 나중에 아는 값은 {@link #withUser(UUID)}·{@link #with(LogField...)}가 만든 새 객체를
 * 다시 {@link #bind(AppLogger)}한다. 예외는 결과 필드 칸 하나다: 요청 하나의 객체들이 같은 칸을 함께 쓰고,
 * 실패 이유를 {@link #addToOutcome(LogField...)}로 맡기면 결과 줄(request completed)에 붙는다.
 */
public final class AppLogger {
    /** MDC 키. {@link EcsLogFormatter}가 같은 이름의 ECS 필드로 옮긴다. */
    static final String TRACE_ID = "trace.id";
    static final String SPAN_ID = "span.id";
    static final String USER_ID = "user.id";

    /** context-propagation에 등록하는 이름. 스레드를 옮기는 실행기(ContextPropagatingTaskDecorator)가 이 값도 함께 옮긴다. */
    static final String CONTEXT_KEY = "loresentry.app-logger";

    private static final ThreadLocal<AppLogger> CURRENT = new ThreadLocal<>();
    /** 바인딩이 없을 때(기동, 프레임워크 콜백) 쓰는 객체. trace는 그때 열려 있는 span이 있으면 그 값을 쓴다. */
    private static final AppLogger DETACHED = new AppLogger(null, null, null, List.of(), null);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final String traceId;
    private final String spanId;
    private final String userId;
    /** 이 객체로 찍는 모든 줄에 붙는 필드(예: 요청 하나의 http.request.id). */
    private final List<LogField> context;
    /**
     * 결과 줄(request completed)에 붙일 필드. 요청 하나에서 만든 객체(withUser·with로 만든 것 포함)가 같은 목록을 함께 쓴다.
     * 채팅 스트리밍처럼 다른 스레드에서 더할 수 있어 스레드 안전한 목록이다. 바인딩이 없는 기본 객체는 null이라 쌓이지 않는다.
     */
    private final List<LogField> outcome;

    /**
     * 요청·flow 시작점에서 만든다.
     *
     * @param traceId 추적 라이브러리가 만든 현재 trace ID(32 hex). 없으면 null
     * @param spanId  현재 span ID(16 hex). 없으면 null
     * @param userId  auth {@code users.id}. 아직 모르면 null
     */
    public AppLogger(String traceId, String spanId, UUID userId) {
        this(traceId, spanId, userId == null ? null : userId.toString(), List.of(), new CopyOnWriteArrayList<>());
    }

    private AppLogger(String traceId, String spanId, String userId, List<LogField> context, List<LogField> outcome) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.userId = userId;
        this.context = context;
        this.outcome = outcome;
    }

    /** 지금 스레드에 바인딩된 객체. 없으면 trace 없는 기본 객체를 돌려주므로 null이 아니다. */
    public static AppLogger current() {
        AppLogger bound = CURRENT.get();
        return bound == null ? DETACHED : bound;
    }

    /**
     * 객체를 지금 스레드에 바인딩한다. try-with-resources로 닫으면 이전 객체와 MDC 값으로 되돌린다.
     * MDC에도 값을 넣어 두어, 같은 스레드에서 프레임워크(Spring, Tomcat 등)가 찍는 줄에도 trace·user가 붙는다.
     */
    public static Binding bind(AppLogger logger) {
        var binding = new Binding(CURRENT.get(), logger.putMdc());
        CURRENT.set(logger);
        return binding;
    }

    /** 사용자를 더한 새 객체. gateway는 세션을 확인한 뒤(SessionFilter) 사용자를 안다. */
    public AppLogger withUser(UUID userId) {
        return new AppLogger(traceId, spanId, userId == null ? null : userId.toString(), context, outcome);
    }

    /** 이 객체로 찍는 모든 줄에 붙일 필드를 더한 새 객체. */
    public AppLogger with(LogField... fields) {
        var merged = new ArrayList<>(context);
        for (LogField field : fields) if (field != null) merged.add(field);
        return new AppLogger(traceId, spanId, userId, List.copyOf(merged), outcome);
    }

    /**
     * 이 요청의 결과 줄(request completed)에 붙일 필드를 맡긴다. 예외 처리기는 실패를 따로 찍지 않고 실패 이유
     * (우리 오류 code, upstream code)를 여기에 맡긴다. 그래서 실패 요청 하나의 결과는 한 줄이고, 에러 건수가 실패 요청 수와 같다.
     * 맡긴 필드가 무엇인지는 결과 줄을 쓰는 쪽이 알 필요가 없다. 바인딩된 요청이 없으면 아무것도 하지 않는다.
     */
    public void addToOutcome(LogField... fields) {
        if (outcome == null) return;
        for (LogField field : fields) if (field != null) outcome.add(field);
    }

    /** 맡겨진 결과 필드. 결과 줄을 쓰는 RequestLogFilter가 읽는다. */
    LogField[] outcomeFields() {
        return outcome == null ? new LogField[0] : outcome.toArray(LogField[]::new);
    }

    /** 응답 헤더 X-Trace-Id·에러 본문 trace_id에 싣는 값. 없으면 null. */
    public String traceId() {
        return traceId;
    }

    /** 정상 동작의 진행 상황. 어느 계층에서 무엇을 처리 중인지와 도메인 ID를 남긴다. */
    public void info(String message, LogField... fields) {
        emit(Level.INFO, message, null, fields);
    }

    /** 처리했지만 예상 밖인 상황(재시도, 충돌, 검증 실패, 건너뛴 이벤트). */
    public void warn(String message, LogField... fields) {
        emit(Level.WARN, message, null, fields);
    }

    /** 예외를 함께 남기는 warn. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void warn(String message, Throwable cause, LogField... fields) {
        emit(Level.WARN, message, cause, fields);
    }

    /** 작업 하나의 실패. 예외 없이 분류만 남길 때는 {@link LogField#errorType(String)}을 함께 넘긴다. */
    public void error(String message, LogField... fields) {
        emit(Level.ERROR, message, null, fields);
    }

    /** 예외로 작업 하나가 실패했을 때. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void error(String message, Throwable cause, LogField... fields) {
        emit(Level.ERROR, message, cause, fields);
    }

    /** context-propagation에 이 저장소를 등록한다. 여러 번 불러도 한 번만 등록된다. */
    static void registerContextAccessor() {
        var registry = ContextRegistry.getInstance();
        boolean registered = registry.getThreadLocalAccessors().stream().anyMatch(a -> CONTEXT_KEY.equals(a.key()));
        if (!registered) registry.registerThreadLocalAccessor(new Accessor());
    }

    private void emit(Level level, String message, Throwable cause, LogField[] fields) {
        // 호출한 클래스·메서드: AppLogger 자신의 프레임을 건너뛴 첫 프레임
        StackWalker.StackFrame caller = WALKER.walk(frames ->
                frames.dropWhile(f -> f.getDeclaringClass() == AppLogger.class).findFirst().orElse(null));
        String loggerName = caller == null ? AppLogger.class.getName() : caller.getClassName();
        var logger = LoggerFactory.getLogger(loggerName);
        if (!logger.isEnabledForLevel(level)) return;
        // 이 줄 동안만 이 객체의 값으로 MDC를 덮고, 찍은 뒤 원래 값으로 되돌린다
        Map<String, String> previous = putMdc();
        try {
            LoggingEventBuilder event = logger.atLevel(level).setMessage(message);
            if (caller != null) event = event.addKeyValue("log.origin.function", caller.getMethodName());
            for (LogField field : context) event = event.addKeyValue(field.name(), field.value());
            for (LogField field : fields) {
                if (field != null) event = event.addKeyValue(field.name(), field.value());
            }
            if (cause != null) event = event.setCause(cause);
            event.log();
        } finally {
            restoreMdc(previous);
        }
    }

    /** trace·span·user 값을 MDC에 넣고, 덮어쓰기 전 값을 돌려준다. 값이 null이면 그 키를 지운다. */
    private Map<String, String> putMdc() {
        Map<String, String> previous = new HashMap<>();
        replace(previous, TRACE_ID, traceId);
        replace(previous, SPAN_ID, spanId);
        replace(previous, USER_ID, userId);
        return previous;
    }

    private static void replace(Map<String, String> previous, String key, String value) {
        previous.put(key, MDC.get(key));
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }

    private static void restoreMdc(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) MDC.remove(key);
            else MDC.put(key, value);
        });
    }

    /** {@link #bind(AppLogger)}의 범위. 닫으면 이전 객체와 MDC 값으로 되돌린다. */
    public static final class Binding implements AutoCloseable {
        private final AppLogger previous;
        private final Map<String, String> previousMdc;

        private Binding(AppLogger previous, Map<String, String> previousMdc) {
            this.previous = previous;
            this.previousMdc = Collections.unmodifiableMap(previousMdc);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            restoreMdc(previousMdc);
        }
    }

    /**
     * 스레드를 옮길 때(예: 채팅 스트리밍의 MVC async 실행기) 이 객체도 함께 옮기는 접근자.
     * 새 스레드에서는 MDC까지 다시 넣어, 그 스레드의 프레임워크 로그에도 trace·user가 붙게 한다.
     */
    private static final class Accessor implements ThreadLocalAccessor<AppLogger> {
        /** setValue 때 덮은 MDC 값. restore에서 되돌린다. */
        private final ThreadLocal<Map<String, String>> savedMdc = new ThreadLocal<>();

        @Override
        public Object key() {
            return CONTEXT_KEY;
        }

        @Override
        public AppLogger getValue() {
            return CURRENT.get();
        }

        @Override
        public void setValue(AppLogger value) {
            savedMdc.set(value.putMdc());
            CURRENT.set(value);
        }

        @Override
        public void setValue() {
            CURRENT.remove();
        }

        @Override
        public void restore(AppLogger previousValue) {
            restoreSavedMdc();
            CURRENT.set(previousValue);
        }

        @Override
        public void restore() {
            restoreSavedMdc();
            CURRENT.remove();
        }

        private void restoreSavedMdc() {
            Map<String, String> saved = savedMdc.get();
            if (saved != null) restoreMdc(saved);
            savedMdc.remove();
        }
    }
}
