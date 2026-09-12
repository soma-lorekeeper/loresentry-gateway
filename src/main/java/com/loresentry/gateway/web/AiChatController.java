package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.AiChatClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiChatController {

    private final AiChatClient aiChatClient;

    private final UpstreamRelay relay;

    public AiChatController(AiChatClient aiChatClient, UpstreamRelay relay) {
        this.aiChatClient = aiChatClient;
        this.relay = relay;
    }

    @GetMapping("/ai-chat")
    public Map<String, Object> aiChat() {
        return relay.describe(aiChatClient);
    }
}
