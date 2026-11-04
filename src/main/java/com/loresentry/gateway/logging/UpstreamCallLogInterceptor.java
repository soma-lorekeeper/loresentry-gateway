package com.loresentry.gateway.logging;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 내부 서비스(authentication·content·graph-rag·ai-chat) 호출 한 번마다 결과를 한 줄 남기는 RestClient interceptor.
 * gateway의 RestClient는 모두 Boot의 RestClient.Builder로 만들므로(RestClientConfig) 여기 한 곳에서 모든 호출이 남는다.
 *
 * <p>남기는 값: 대상 호스트({@code url.domain}), 대상 주소({@code url.full}, 쿼리 제외), 응답 상태, 걸린 시간.
 * {@code url.path}·{@code http.route}·{@code http.request.method}는 RequestLogFilter가 붙인 브라우저 요청의 값이 그대로 들어간다
 * (보낸 호출의 메서드는 남기지 않는다).
 * 요청·응답 본문과 헤더(X-User-Display-Name 등)는 남기지 않는다. 대상의 5xx와 연결 실패는 warn, 그 밖은 info다.
 * 5xx의 error.type은 {@code UPSTREAM_SERVER_ERROR}, 연결 실패는 예외 클래스와 메시지 없는 스택이다(LogFlow.call의 결과 줄과 같다).
 *
 * <p>줄 위치(log.logger·log.origin.function)는 이 interceptor가 아니라 실제로 HTTP를 보낸 Spring RestClient 메서드다
 * (예: {@code DefaultRestClient$DefaultResponseSpec.body}). 그 메서드를 부른 우리 client 메서드는 바로 앞 진입 줄에 있다.
 *
 * <p>span(LOG_SCHEMA.md 7절 "외부 호출"): 호출을 {@code upstream call} span(INTERNAL)으로 감싸 지금 문맥으로 두고, 결과 줄도 그 span 안에서
 * 찍는다. EDOT agent의 Apache HttpClient 계측이 여는 HTTP CLIENT span이 그 자식이 되고, 그 계측이 CLIENT span의 문맥으로 traceparent
 * 헤더를 넣는다(이 interceptor는 헤더를 넣지 않는다). 그래서 결과 줄의 span.id로 APM의 그 호출을 바로 찾는다. 대상의 5xx와 연결 실패면
 * span 상태가 ERROR다. {@link LogFlow#call}과 같은 span이다.
 */
// log.layer: 이름 끝 규칙으로는 interceptor지만, 내부 서비스 호출 결과를 남기는 줄이라 실제 역할인 client로 지정한다
@LogLayer("client")
final class UpstreamCallLogInterceptor implements ClientHttpRequestInterceptor {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** 우리 코드의 패키지. logging 패키지(이 interceptor, AppLogger)는 우리 코드로 보지 않는다. */
    private static final String OUR_CODE = "com.loresentry.gateway.";
    private static final String LOGGING = "com.loresentry.gateway.logging.";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        AppLogger logger = AppLogger.current();
        StackWalker.StackFrame restClient = restClientCall();
        URI uri = request.getURI();
        // 호출을 감싸는 span을 먼저 열고 걸린 시간을 재기 시작한다(LogFlow.call과 같은 순서. span 길이와 event.duration이 맞는다)
        Span span = TraceContext.tracer().spanBuilder("upstream call").startSpan();
        long started = System.nanoTime();
        try (Scope ignored = span.makeCurrent()) {
            ClientHttpResponse response;
            try {
                response = execution.execute(request, body);
            } catch (IOException failure) {
                // 실패 줄도 span 안에서 찍는다(이 catch는 span의 문맥 범위 안에 있다)
                logger.warnAt(restClient, "upstream call failed", LogField.urlDomain(uri.getHost()), LogField.urlFull(uri),
                        LogField.errorType(failure.getClass().getName()), LogField.safeStackTrace(failure),
                        LogField.duration(elapsed(started)));
                TraceContext.failed(span, failure);
                throw failure;
            }
            int status = response.getStatusCode().value();
            // 대상의 5xx는 warn이고 error.type이 UPSTREAM_SERVER_ERROR다. 그 밖은 error.type이 없다(null 필드는 줄에서 빠진다)
            LogField[] fields = {LogField.urlDomain(uri.getHost()), LogField.urlFull(uri), LogField.statusCode(status),
                    LogField.errorType(status >= 500 ? LogFlow.UPSTREAM_SERVER_ERROR : null), LogField.duration(elapsed(started))};
            if (status >= 500) {
                logger.warnAt(restClient, "upstream call completed", fields);
                span.setStatus(StatusCode.ERROR, LogFlow.UPSTREAM_SERVER_ERROR);
            } else {
                logger.infoAt(restClient, "upstream call completed", fields);
            }
            return response;
        } finally {
            span.end();
        }
    }

    /**
     * 우리 코드가 부른 RestClient 메서드의 프레임. 콜 스택을 위(최근)에서부터 보며 처음 만나는 우리 코드(logging 패키지 밖) 바로 위
     * 프레임이다. 예: ContentApiClient.saveContent → DefaultRestClient$DefaultResponseSpec.body → … → 이 interceptor.
     * 우리 코드를 만나지 못하면(테스트 등) null이고, 그때 줄 위치는 이 interceptor다.
     */
    private static StackWalker.StackFrame restClientCall() {
        return WALKER.walk(frames -> {
            StackWalker.StackFrame previous = null;
            for (Iterator<StackWalker.StackFrame> it = frames.iterator(); it.hasNext(); ) {
                StackWalker.StackFrame frame = it.next();
                String type = frame.getClassName();
                if (type.startsWith(OUR_CODE) && !type.startsWith(LOGGING)) return previous;
                previous = frame;
            }
            return null;
        });
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
