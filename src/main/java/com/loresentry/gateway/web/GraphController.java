package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.GraphRagClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GraphController {

    private final GraphRagClient graphRagClient;

    private final UpstreamRelay relay;

    public GraphController(GraphRagClient graphRagClient, UpstreamRelay relay) {
        this.graphRagClient = graphRagClient;
        this.relay = relay;
    }

    @GetMapping("/graph")
    public Map<String, Object> graph() {
        return relay.describe(graphRagClient);
    }
}
