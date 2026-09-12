package com.loresentry.gateway.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AiChatClient extends UpstreamClient {

    public AiChatClient(RestClient aiChatRestClient) {
        super("ai-chat", aiChatRestClient);
    }
}
