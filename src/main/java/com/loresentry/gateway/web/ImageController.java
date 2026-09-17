package com.loresentry.gateway.web;

import java.util.Map;
import java.util.UUID;

import com.loresentry.gateway.client.ContentClient;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects/{projectId}/images")
public class ImageController {

    private final ContentClient contentClient;

    public ImageController(ContentClient contentClient) {
        this.contentClient = contentClient;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createUpload(
            @PathVariable UUID projectId,
            @RequestBody Map<String, Object> request) {
        return forward(contentClient.createImageUpload(projectId, request));
    }

    @PostMapping("/{imageId}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable UUID projectId, @PathVariable UUID imageId) {
        return forward(contentClient.completeImageUpload(projectId, imageId));
    }

    @GetMapping("/{imageId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID projectId, @PathVariable UUID imageId) {
        return forward(contentClient.getImage(projectId, imageId));
    }

    private static ResponseEntity<Map<String, Object>> forward(ResponseEntity<Map<String, Object>> upstream) {
        return ResponseEntity.status(upstream.getStatusCode()).body(upstream.getBody());
    }
}
