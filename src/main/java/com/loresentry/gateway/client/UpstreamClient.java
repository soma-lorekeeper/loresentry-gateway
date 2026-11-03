package com.loresentry.gateway.client;

import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.SkipEntryLog;

public abstract class UpstreamClient {

    /** 내부 서비스가 신원을 읽는 헤더. authentication·content와 같은 이름이다. */
    public static final String USER_ID_HEADER = "X-User-Id";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private final String name;

    private final RestClient restClient;

    protected UpstreamClient(String name, RestClient restClient) {
        this.name = name;
        this.restClient = restClient;
    }

    @SkipEntryLog("ProbeService가 대상을 고르고 정렬할 때 여러 번 부르는 getter다. 대상은 그다음 upstream 호출 줄의 url.domain으로 보인다")
    public String name() {
        return name;
    }

    /** RestClient for fixed diagnostic paths; domain API clients use typed URI templates. */
    public static RestClient restClient(RestClient.Builder builder, String baseUrl) {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(baseUrl);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);
        return builder.uriBuilderFactory(factory).build();
    }

    public Map<String, Object> describe() {
        AppLogger.current().info("upstream service describe call");
        return get("/");
    }

    public Map<String, Object> health() {
        AppLogger.current().info("upstream health probe call");
        return get("/health");
    }

    public Map<String, Object> databaseHealth() {
        AppLogger.current().info("upstream database health probe call");
        try {
            return restClient.get()
                    .uri("/health/db")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                    })
                    .body(JSON_OBJECT);
        } catch (RestClientException exception) {
            throw new UpstreamException(name, name + " call to /health/db failed", exception);
        }
    }

    protected Map<String, Object> get(String uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (RestClientException exception) {
            throw new UpstreamException(name, name + " call to " + uri + " failed", exception);
        }
    }
}
