package com.loresentry.gateway.web;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.loresentry.gateway.client.UpstreamClient;
import com.loresentry.gateway.client.UpstreamException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DbHealthController {

    private final List<UpstreamClient> clients;

    public DbHealthController(List<UpstreamClient> clients) {
        this.clients = clients;
    }

    @GetMapping("/health/db")
    public ResponseEntity<Map<String, Object>> databaseHealth() {
        Map<String, Object> upstreams = new LinkedHashMap<>();
        boolean healthy = true;

        List<UpstreamClient> ordered = clients.stream()
                .sorted(Comparator.comparing(UpstreamClient::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (UpstreamClient client : ordered) {
            Map<String, Object> result = describe(client);
            upstreams.put(client.name(), result);

            if (!"ok".equals(result.get("status"))) {
                healthy = false;
            }
        }

        HttpStatus status = healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(status).body(Map.of(
                "status", healthy ? "ok" : "degraded",
                "service", "gateway-api",
                "upstreams", upstreams));
    }

    private Map<String, Object> describe(UpstreamClient client) {
        try {
            Map<String, Object> body = client.databaseHealth();
            return body == null ? Map.of("status", "error", "error", "empty response") : body;
        } catch (UpstreamException exception) {
            return Map.of("status", "error", "error", String.valueOf(exception.getMessage()));
        }
    }
}
