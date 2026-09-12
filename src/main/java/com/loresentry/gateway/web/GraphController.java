package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.client.GraphRagClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GraphController {

    private final GraphRagClient graphRagClient;

    public GraphController(GraphRagClient graphRagClient) {
        this.graphRagClient = graphRagClient;
    }

    @GetMapping("/graph")
    public Map<String, Object> graph() {
        Map<String, Object> upstream = graphRagClient.describe();

        return Map.of(
                "service", "gateway-api",
                "thread", Thread.currentThread().toString(),
                "upstream", Map.of("graph-rag", upstream));
    }
}
