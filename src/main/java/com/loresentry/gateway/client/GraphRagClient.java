package com.loresentry.gateway.client;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GraphRagClient {

    private static final String UPSTREAM = "graph-rag";

    private final RestClient restClient;

    public GraphRagClient(RestClient graphRagRestClient) {
        this.restClient = graphRagRestClient;
    }

    public Map<String, Object> describe() {
        try {
            return restClient.get()
                    .uri("/")
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException exception) {
            throw new UpstreamException(UPSTREAM, "graph-rag call failed", exception);
        }
    }

    public Map<String, Object> health() {
        try {
            return restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException exception) {
            throw new UpstreamException(UPSTREAM, "graph-rag health call failed", exception);
        }
    }
}
