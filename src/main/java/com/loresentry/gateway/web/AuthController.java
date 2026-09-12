package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.AuthenticationClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthenticationClient authenticationClient;

    private final UpstreamRelay relay;

    public AuthController(AuthenticationClient authenticationClient, UpstreamRelay relay) {
        this.authenticationClient = authenticationClient;
        this.relay = relay;
    }

    @GetMapping("/auth")
    public Map<String, Object> auth() {
        return relay.describe(authenticationClient);
    }
}
