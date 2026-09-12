package com.loresentry.gateway.client;

import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public abstract class UpstreamClient {

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
