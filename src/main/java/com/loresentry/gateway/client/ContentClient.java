package com.loresentry.gateway.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ContentClient extends UpstreamClient {

    public ContentClient(RestClient contentRestClient) {
        super("content", contentRestClient);
    }
}
