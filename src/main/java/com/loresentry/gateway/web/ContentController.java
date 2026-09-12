package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.ContentClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContentController {

    private final ContentClient contentClient;

    private final UpstreamRelay relay;

    public ContentController(ContentClient contentClient, UpstreamRelay relay) {
        this.contentClient = contentClient;
        this.relay = relay;
    }

    @GetMapping("/content")
    public Map<String, Object> content() {
        return relay.describe(contentClient);
    }
}
