package com.loresentry.gateway.client;

import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.DefaultUriBuilderFactory;

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

    public String name() {
        return name;
    }

    /**
     * 업스트림 {@link RestClient}를 만든다. 인코딩을 끄는 것이 요점이다 — 중계하는 경로와 쿼리는
     * 서블릿이 이미 인코딩한 값이라, 빌더가 한 번 더 인코딩하면 {@code %EC}가 {@code %25EC}가 된다.
     *
     * <p>설정과 테스트가 같은 방식으로 클라이언트를 만들어야 하므로 여기 둔다.
     */
    public static RestClient restClient(RestClient.Builder builder, String baseUrl) {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(baseUrl);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        return builder.uriBuilderFactory(factory).build();
    }

    public Map<String, Object> describe() {
        return get("/");
    }

    public Map<String, Object> health() {
        return get("/health");
    }

    public Map<String, Object> databaseHealth() {
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

    /**
     * 업스트림 응답을 손대지 않고 통과시킨다. 상태 코드와 오류 본문을 그대로 넘기는 것이 요점이다 —
     * gateway가 다시 포장하면 클라이언트가 두 겹을 벗겨야 하고, 업스트림이 준 이유가 지워진다.
     *
     * <p>클라이언트의 헤더를 통째로 전달하지 않는다. {@code forwardedHeaders}는 API 계약에 속한
     * 헤더만 담은 허용 목록이고, 신원은 {@code userId} 하나로 받는다. 신원 헤더가 그 목록에 섞여
     * 들어와도 여기서 버리므로, 클라이언트가 보낸 {@code X-User-Id}는 어떤 경로로도 업스트림에
     * 닿지 않는다.
     */
    public ResponseEntity<byte[]> forward(HttpMethod method, String path, String query, byte[] body,
            MediaType contentType, UUID userId, Map<String, String> forwardedHeaders) {
        try {
            // uri(String)은 {} 를 템플릿 변수로 확장한다. builder 형태는 확장하지 않으므로
            // 이미 인코딩된 서블릿 경로를 그대로 보낼 수 있다.
            RestClient.RequestBodySpec request = restClient.method(method)
                    .uri(builder -> builder.replacePath(path).replaceQuery(query).build());

            // header() 는 덮어쓰지 않고 값을 추가한다. 그래서 신원 헤더는 순서로 이기려 하지 않고
            // 아예 걸러낸다 — 두 값이 실리면 업스트림이 어느 것이 gateway 의 것인지 알 수 없다.
            forwardedHeaders.forEach((name, value) -> {
                if (!USER_ID_HEADER.equalsIgnoreCase(name)) {
                    request.header(name, value);
                }
            });
            request.header(USER_ID_HEADER, userId.toString());

            if (body != null && body.length > 0) {
                request.contentType(contentType == null ? MediaType.APPLICATION_JSON : contentType)
                        .body(body);
            }

            ResponseEntity<byte[]> upstream = request.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                    })
                    .toEntity(byte[].class);

            return copyOf(upstream);
        } catch (RestClientException exception) {
            throw new UpstreamException(name, name + " call to " + path + " failed", exception);
        }
    }

    /**
     * 업스트림 헤더를 전부 옮기지 않는다. {@code Transfer-Encoding}이나 {@code Content-Length}를 함께
     * 넘기면 서블릿 컨테이너가 다시 계산한 값과 어긋난다.
     */
    private static ResponseEntity<byte[]> copyOf(ResponseEntity<byte[]> upstream) {
        HttpHeaders headers = new HttpHeaders();
        HttpHeaders original = upstream.getHeaders();
        if (original.getContentType() != null) {
            headers.setContentType(original.getContentType());
        }
        if (original.getFirst(HttpHeaders.LOCATION) != null) {
            headers.set(HttpHeaders.LOCATION, original.getFirst(HttpHeaders.LOCATION));
        }
        if (original.getFirst(HttpHeaders.CACHE_CONTROL) != null) {
            headers.set(HttpHeaders.CACHE_CONTROL, original.getFirst(HttpHeaders.CACHE_CONTROL));
        }
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
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
