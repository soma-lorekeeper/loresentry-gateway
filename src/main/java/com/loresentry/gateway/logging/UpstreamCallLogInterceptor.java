package com.loresentry.gateway.logging;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 내부 서비스(authentication·content·graph-rag·ai-chat) 호출 한 번마다 결과를 한 줄 남기는 RestClient interceptor.
 * gateway의 RestClient는 모두 Boot의 RestClient.Builder로 만들므로(RestClientConfig) 여기 한 곳에서 모든 호출이 남는다.
 *
 * <p>남기는 값: 대상 호스트({@code url.domain}), 메서드, 쿼리를 뺀 경로, 응답 상태, 걸린 시간. 요청·응답 본문과 헤더
 * (X-User-Display-Name 등)는 남기지 않는다. 대상의 5xx와 연결 실패는 warn, 그 밖은 info다.
 *
 * <p>traceparent 헤더는 이 interceptor가 아니라 RestClient observation이 붙인다.
 */
// log.layer: 이름 끝 규칙으로는 interceptor지만, 내부 서비스 호출 결과를 남기는 줄이라 실제 역할인 client로 지정한다
@LogLayer("client")
final class UpstreamCallLogInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        AppLogger logger = AppLogger.current();
        URI uri = request.getURI();
        long started = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            LogField[] fields = {LogField.urlDomain(uri.getHost()), LogField.httpMethod(request.getMethod().name()),
                    LogField.urlPath(uri.getRawPath()), LogField.statusCode(status), LogField.duration(elapsed(started))};
            if (status >= 500) logger.warn("upstream call completed", fields);
            else logger.info("upstream call completed", fields);
            return response;
        } catch (IOException failure) {
            logger.warn("upstream call failed", LogField.urlDomain(uri.getHost()),
                    LogField.httpMethod(request.getMethod().name()), LogField.urlPath(uri.getRawPath()),
                    LogField.errorType(failure.getClass().getName()), LogField.duration(elapsed(started)));
            throw failure;
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
