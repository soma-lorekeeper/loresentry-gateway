package com.loresentry.gateway.logging;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import com.loresentry.gateway.security.SessionFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 브라우저 요청 하나의 시작점. 요청마다 {@link AppLogger}를 만들어 바인딩하고 접근 로그를 남긴다.
 *
 * <p>순서: TraceContextHidingFilter(HIGHEST_PRECEDENCE) → Spring observation 필터(+1, 서버 span 시작) → 이 필터(+2) →
 * CsrfFilter(+5)·CorsFilter(+10)·SessionFilter(+20). 그래서 CSRF·세션 필터가 직접 쓰는 403·401 응답도 이 필터의 완료 로그에
 * 남고, 그 필터들 안의 로그에도 trace가 붙는다.
 *
 * <p>하는 일
 * <ol>
 *   <li>observation 필터가 연 서버 span에서 traceId·spanId를 꺼내 {@code new AppLogger(traceId, spanId, null)}로 만든다.
 *       ALB의 X-Amzn-Trace-Id는 모든 줄에 {@code http.request.id}로 붙인다.</li>
 *   <li>응답 헤더 X-Trace-Id에 traceId를 싣는다. 문의 받은 값을 Kibana에서 바로 찾는다.</li>
 *   <li>"request received"와 "request completed"(경로 템플릿 http.route·상태·걸린 시간)를 남긴다. 헬스체크도 같다. 헬스체크
 *       요청은 그 요청의 모든 줄에 {@code tags: health}가 붙고, 정상(info) 줄은 Logstash가 버린다. 결과 줄에는 예외 처리기·보안 응답이
 *       {@link AppLogger#addToOutcome(LogField...)}로 맡긴 실패 이유(error.type 등)도 붙인다. 실패를 따로 찍지 않으므로 요청 하나의
 *       결과는 늘 한 줄이다. 맡긴 필드가 무엇인지는 이 필터가 보지 않는다. 사용자는 SessionFilter가 요청 속성에 넣은
 *       값을 완료 시점에 읽는다. 채팅 스트리밍처럼 비동기로 끝나는 요청은 비동기 완료 때 남긴다.</li>
 * </ol>
 *
 * <p>쿼리스트링은 남기지 않는다(OAuth code·state, 검색어). 경로만 {@code url.path}에 넣는다.
 */
public final class RequestLogFilter extends OncePerRequestFilter {
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** Boot 기본 오류 응답(/error)에도 trace_id를 싣기 위해 요청 속성에 남기는 이름. */
    public static final String TRACE_ATTRIBUTE = RequestLogFilter.class.getName() + ".traceId";
    private static final String ALB_TRACE_HEADER = "X-Amzn-Trace-Id";

    private final Tracer tracer;

    public RequestLogFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        String spanId = span == null ? null : span.context().spanId();
        AppLogger logger = new AppLogger(traceId, spanId, null).with(LogField.requestId(request.getHeader(ALB_TRACE_HEADER)));
        String path = request.getRequestURI();
        // 헬스체크 흐름의 모든 줄에 tags: health를 붙인다. 정상(info) 줄은 Logstash가 버리고, 실패한 헬스체크의 warn·error는 남는다
        if (health(path)) logger = logger.withTag("health");
        if (traceId != null) {
            response.setHeader(TRACE_HEADER, traceId);
            request.setAttribute(TRACE_ATTRIBUTE, traceId);
        }
        long started = System.nanoTime();
        try (var bound = AppLogger.bind(logger)) {
            logger.info("request received", LogField.httpMethod(request.getMethod()), LogField.urlPath(path));
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException failure) {
                // 컨트롤러 예외 처리기가 잡지 못한 예외. 이 뒤 Tomcat이 /error로 500을 쓴다
                withUser(logger, request).error("request failed with an unhandled exception", failure,
                        concat(new LogField[]{LogField.httpMethod(request.getMethod()), LogField.urlPath(path),
                                LogField.statusCode(500), LogField.duration(elapsed(started))}, logger.outcomeFields()));
                throw failure;
            }
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new Completion(logger, request, response, path, started));
            } else {
                completed(withUser(logger, request), request, response.getStatus(), path, started);
            }
        }
    }

    /**
     * 결과 줄. 응답 상태로 레벨을 정한다: 5xx error, 4xx warn, 그 외 info. 2xx 헬스체크 줄은 Logstash가 버린다.
     * 기본 필드(메서드, 경로, 상태, 걸린 시간) 뒤에 이 요청에 맡겨진 결과 필드를 그대로 붙인다.
     */
    private static void completed(AppLogger logger, HttpServletRequest request, int status, String path, long started) {
        // http.route: Spring이 고른 경로 템플릿. 컨트롤러까지 가지 못한 요청(CSRF·세션 거절, 없는 경로)에는 없다
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        LogField[] fields = concat(new LogField[]{LogField.httpMethod(request.getMethod()), LogField.urlPath(path),
                LogField.route(route instanceof String pattern ? pattern : null), LogField.statusCode(status),
                LogField.duration(elapsed(started))}, logger.outcomeFields());
        if (status >= 500) logger.error("request completed", fields);
        else if (status >= 400) logger.warn("request completed", fields);
        else logger.info("request completed", fields);
    }

    /** SessionFilter가 세션을 확인했으면 그 사용자를 더한다. 공개 경로(로그인 등)는 사용자가 없다. */
    private static AppLogger withUser(AppLogger logger, HttpServletRequest request) {
        return request.getAttribute(SessionFilter.USER_ATTRIBUTE) instanceof UUID user ? logger.withUser(user) : logger;
    }

    private static boolean health(String path) {
        return "/health".equals(path) || path.startsWith("/health/");
    }

    private static LogField[] concat(LogField[] first, LogField[] second) {
        LogField[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    /** 비동기 요청(채팅 SSE)이 끝났을 때 완료 로그를 남긴다. 이 콜백은 다른 스레드에서 불릴 수 있어 logger를 직접 들고 있다. */
    private static final class Completion implements AsyncListener {
        private final AppLogger logger;
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String path;
        private final long started;

        Completion(AppLogger logger, HttpServletRequest request, HttpServletResponse response, String path, long started) {
            this.logger = logger;
            this.request = request;
            this.response = response;
            this.path = path;
            this.started = started;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            completed(withUser(logger, request), request, response.getStatus(), path, started);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            withUser(logger, request).warn("async request timed out", LogField.httpMethod(request.getMethod()),
                    LogField.urlPath(path), LogField.duration(elapsed(started)));
        }

        @Override
        public void onError(AsyncEvent event) {
            withUser(logger, request).error("async request failed", event.getThrowable(),
                    LogField.httpMethod(request.getMethod()), LogField.urlPath(path), LogField.duration(elapsed(started)));
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
        }
    }
}
