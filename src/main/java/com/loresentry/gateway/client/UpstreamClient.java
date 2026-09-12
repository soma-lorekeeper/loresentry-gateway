package com.loresentry.gateway.client;

import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
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
