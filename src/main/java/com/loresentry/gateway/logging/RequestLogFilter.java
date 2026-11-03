package com.loresentry.gateway.logging;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

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
 *       요청 하나의 모든 줄에 ALB의 X-Amzn-Trace-Id({@code http.request.id}), 브라우저가 부른 경로({@code url.path}),
 *       경로 템플릿({@code http.route})을 붙인다. 경로 템플릿은 Spring MVC가 나중에 고를 것을 요청 시작 때 미리 찾는다.</li>
 *   <li>응답 헤더 X-Trace-Id에 traceId를 싣는다. 문의 받은 값을 Kibana에서 바로 찾는다.</li>
 *   <li>"request received"와 "request completed"(경로 템플릿 http.route·상태·걸린 시간)를 남긴다. 헬스체크도 같다. 헬스체크
 *       요청은 그 요청의 모든 줄에 {@code tags: health}가 붙고, 정상(info) 줄은 Logstash가 버린다. 결과 줄에는 예외 처리기·보안 응답이
 *       {@link AppLogger#addToOutcome(LogField...)}로 맡긴 실패 이유(error.type 등)도 붙인다. 실패를 따로 찍지 않으므로 요청 하나의
 *       결과는 늘 한 줄이다. 맡긴 필드가 무엇인지는 이 필터가 보지 않는다. 사용자는 SessionFilter가 요청 속성에 넣은
 *       값을 완료 시점에 읽는다. 채팅 스트리밍처럼 비동기로 끝나는 요청은 비동기 완료 때 남긴다. 비동기 시간 초과·오류도
 *       이유를 결과 줄에 맡기고 원인 줄(warn, 브라우저 연결 끊김은 info)만 따로 남긴다.</li>
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
    /** 경로 템플릿을 미리 찾는 데 쓰는 컨트롤러 매핑. MVC가 없는 테스트 문맥에서는 비어 있고, 그때는 http.route를 붙이지 않는다. */
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public RequestLogFilter(Tracer tracer, ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.tracer = tracer;
        this.mappings = mappings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        String spanId = span == null ? null : span.context().spanId();
        String path = request.getRequestURI();
        AppLogger logger = new AppLogger(traceId, spanId, null).with(LogField.requestId(request.getHeader(ALB_TRACE_HEADER)),
                LogField.urlPath(path), LogField.route(route(request)));
        // 헬스체크 흐름의 모든 줄에 tags: health를 붙인다. 정상(info) 줄은 Logstash가 버리고, 실패한 헬스체크의 warn·error는 남는다
        if (health(path)) logger = logger.withTag("health");
        if (traceId != null) {
            response.setHeader(TRACE_HEADER, traceId);
            request.setAttribute(TRACE_ATTRIBUTE, traceId);
        }
        long started = System.nanoTime();
        try (var bound = AppLogger.bind(logger)) {
            logger.info("request received", LogField.httpMethod(request.getMethod()));
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException failure) {
                // 컨트롤러 예외 처리기가 잡지 못한 예외. 이 뒤 Tomcat이 /error로 500을 쓴다
                withUser(logger, request).error("request failed with an unhandled exception", failure,
                        concat(new LogField[]{LogField.httpMethod(request.getMethod()), LogField.statusCode(500),
                                LogField.duration(elapsed(started))}, logger.outcomeFields()));
                throw failure;
            }
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new Completion(logger, request, response, started));
            } else {
                completed(withUser(logger, request), request, response.getStatus(), started);
            }
        }
    }

    /**
     * 결과 줄. 응답 상태로 레벨을 정한다: 5xx error, 4xx warn, 그 외 info. 2xx 헬스체크 줄은 Logstash가 버린다.
     * 기본 필드(메서드, 상태, 걸린 시간) 뒤에 이 요청에 맡겨진 결과 필드를 그대로 붙인다. url.path·http.route는 문맥 필드로 이미 붙어 있다.
     */
    private static void completed(AppLogger logger, HttpServletRequest request, int status, long started) {
        LogField[] fields = concat(new LogField[]{LogField.httpMethod(request.getMethod()), LogField.statusCode(status),
                LogField.duration(elapsed(started))}, logger.outcomeFields());
        if (status >= 500) logger.error("request completed", fields);
        else if (status >= 400) logger.warn("request completed", fields);
        else logger.info("request completed", fields);
    }

    /**
     * Spring MVC가 이 요청에 고를 경로 템플릿(예: /files/{fileId}/content). DispatcherServlet이 나중에 같은 매핑으로 똑같이 고르므로
     * 결과가 같다. 그래서 세션 확인처럼 컨트롤러 앞에서 찍는 줄과, 세션·CSRF로 거절된 요청에도 템플릿이 붙는다.
     *
     * <p>매칭은 요청 속성(파싱한 경로, 고른 handler·템플릿 등)을 남긴다. 뒤의 필터와 DispatcherServlet이 보는 상태가 바뀌지 않도록
     * 매칭 전 속성을 기억해 두었다가 그대로 되돌린다. 맞는 경로가 없거나(404) 매칭 중 예외(405 등)가 나면 null이다.
     */
    private String route(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
        if (mapping == null) return null;
        Map<String, Object> before = new HashMap<>();
        for (String name : Collections.list(request.getAttributeNames())) before.put(name, request.getAttribute(name));
        try {
            // 경로 패턴 매칭은 파싱한 요청 경로를 요청 속성에서 읽는다. DispatcherServlet도 나중에 다시 파싱한다
            ServletRequestPathUtils.parseAndCache(request);
            if (mapping.getHandler(request) == null) return null;
            return request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern ? pattern : null;
        } catch (Exception noMatch) {
            return null;
        } finally {
            // 매칭이 새로 만든 속성은 지우고, 바꾸거나 지운 속성은 원래 값으로 되돌린다
            for (String name : Collections.list(request.getAttributeNames())) {
                if (!before.containsKey(name)) request.removeAttribute(name);
            }
            before.forEach(request::setAttribute);
        }
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

    /**
     * 비동기 요청(채팅 SSE)이 끝났을 때 완료 로그를 남긴다. 이 콜백은 다른 스레드에서 불릴 수 있어 logger를 직접 들고 있다.
     *
     * <p>결과 줄(request completed)은 onComplete 한 곳에서만 찍는다. 시간 초과·오류는 서블릿 규약상 그 뒤에 onComplete가 오므로,
     * 그 자리에서는 이유를 결과 줄에 맡기고(addToOutcome) 원인 줄만 남긴다. 그래서 비동기 요청도 결과는 한 줄이다.
     */
    private static final class Completion implements AsyncListener {
        private final AppLogger logger;
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final long started;

        Completion(AppLogger logger, HttpServletRequest request, HttpServletResponse response, long started) {
            this.logger = logger;
            this.request = request;
            this.response = response;
            this.started = started;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            completed(withUser(logger, request), request, response.getStatus(), started);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            logger.addToOutcome(LogField.errorType("ASYNC_REQUEST_TIMEOUT"));
            withUser(logger, request).warn("async request timed out", LogField.httpMethod(request.getMethod()),
                    LogField.duration(elapsed(started)));
        }

        @Override
        public void onError(AsyncEvent event) {
            Throwable failure = event.getThrowable();
            String type = failure == null ? null : failure.getClass().getName();
            logger.addToOutcome(LogField.errorType(type));
            // 브라우저가 연결을 끊은 것(탭 닫기·새로고침)은 서버 실패가 아니라 info로 남긴다. 예외 메시지는 남기지 않는다
            boolean disconnected = failure instanceof AsyncRequestNotUsableException || (type != null && type.contains("ClientAbort"));
            if (disconnected) withUser(logger, request).info("async request client disconnected", LogField.errorType(type),
                    LogField.httpMethod(request.getMethod()), LogField.duration(elapsed(started)));
            else withUser(logger, request).warn("async request failed", LogField.errorType(type),
                    LogField.httpMethod(request.getMethod()), LogField.duration(elapsed(started)));
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
        }
    }
}
