package com.loresentry.gateway.client;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ContentClient extends UpstreamClient {

    public ContentClient(RestClient contentRestClient) {
        super("content", contentRestClient);
    }

    public ResponseEntity<Map<String, Object>> createImageUpload(UUID projectId, Map<String, Object> request) {
        return relayPost("/projects/{projectId}/images", request, projectId);
    }

    public ResponseEntity<Map<String, Object>> completeImageUpload(UUID projectId, UUID imageId) {
        return relayPost("/projects/{projectId}/images/{imageId}/complete", null, projectId, imageId);
    }

    public ResponseEntity<Map<String, Object>> getImage(UUID projectId, UUID imageId) {
        return relayGet("/projects/{projectId}/images/{imageId}", projectId, imageId);
    }
}
