package com.loresentry.gateway.application;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Objects;
import com.loresentry.gateway.client.UpstreamClient;
import com.loresentry.gateway.client.UpstreamException;
import org.springframework.stereotype.Service;

@Service
public class ProbeService {
    private final List<UpstreamClient> clients;
    public ProbeService(List<UpstreamClient> clients) { this.clients = clients; }
    public Map<String, Object> describe(String name) {
        var client = clients.stream().filter(c -> Objects.equals(name, c.name())).findFirst().orElseThrow();
        return Map.of("service", "gateway-api", "thread", Thread.currentThread().toString(), "upstream", Map.of(name, client.describe()));
    }
    public record Health(String status, String service, Map<String, Object> upstreams) {}
    public Health databaseHealth() {
        Map<String, Object> results = new LinkedHashMap<>();
        boolean healthy = true;
        for (var client : clients.stream().sorted(Comparator.comparing(UpstreamClient::name, Comparator.nullsLast(Comparator.naturalOrder()))).toList()) {
            Map<String, Object> result;
            try { result = client.databaseHealth(); }
            catch (UpstreamException failure) { result = Map.of("status", "error", "error", "upstream_unavailable"); }
            if (result == null) result = Map.of("status", "error", "error", "empty_response");
            results.put(client.name(), result);
            healthy &= "ok".equals(result.get("status"));
        }
        return new Health(healthy ? "ok" : "degraded", "gateway-api", results);
    }
}
