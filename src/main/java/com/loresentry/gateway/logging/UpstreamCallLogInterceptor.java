package com.loresentry.gateway.logging;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 내부 서비스(authentication·content·graph-rag·ai-chat) 호출 한 번마다 결과를 한 줄 남기는 RestClient interceptor.
 * gateway의 RestClient는 모두 Boot의 RestClient.Builder로 만들므로(RestClientConfig) 여기 한 곳에서 모든 호출이 남는다.
 *
 * <p>남기는 값: 대상 호스트({@code url.domain}), 대상 주소({@code url.full}, 쿼리 제외), 메서드, 응답 상태, 걸린 시간.
 * {@code url.path}·{@code http.route}는 RequestLogFilter가 붙인 브라우저 요청의 값이 그대로 들어간다.
 * 요청·응답 본문과 헤더(X-User-Display-Name 등)는 남기지 않는다. 대상의 5xx와 연결 실패는 warn, 그 밖은 info다.
 *
 * <p>줄 위치(log.logger·log.origin.function)는 이 interceptor가 아니라 실제로 HTTP를 보낸 Spring RestClient 메서드다
 * (예: {@code DefaultRestClient$DefaultResponseSpec.body}). 그 메서드를 부른 우리 client 메서드는 바로 앞 진입 줄에 있다.
 *
 * <p>traceparent 헤더는 이 interceptor가 아니라 RestClient observation이 붙인다.
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
        long started = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            LogField[] fields = {LogField.urlDomain(uri.getHost()), LogField.urlFull(uri), LogField.httpMethod(request.getMethod().name()),
                    LogField.statusCode(status), LogField.duration(elapsed(started))};
            if (status >= 500) logger.warnAt(restClient, "upstream call completed", fields);
            else logger.infoAt(restClient, "upstream call completed", fields);
            return response;
        } catch (IOException failure) {
            logger.warnAt(restClient, "upstream call failed", LogField.urlDomain(uri.getHost()), LogField.urlFull(uri),
                    LogField.httpMethod(request.getMethod().name()), LogField.errorType(failure.getClass().getName()),
                    LogField.duration(elapsed(started)));
            throw failure;
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
