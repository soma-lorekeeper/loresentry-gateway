package com.loresentry.gateway.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AuthenticationClient extends UpstreamClient {

    public AuthenticationClient(RestClient authenticationRestClient) {
        super("authentication", authenticationRestClient);
    }
}
