package com.loresentry.gateway.application;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import com.loresentry.gateway.client.UpstreamClient;
import com.loresentry.gateway.client.UpstreamException;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import org.springframework.stereotype.Service;

@Service
public class ProbeService {
    /** 내부 서비스 이름 허용 목록. client/의 AiChatClient·AuthenticationClient·ContentClient·GraphRagClient가 넘기는 이름이다. */
    private static final Set<String> UPSTREAMS = Set.of("ai-chat", "authentication", "content", "graph-rag");
    private final List<UpstreamClient> clients;
    public ProbeService(List<UpstreamClient> clients) { this.clients = clients; }
    public Map<String, Object> describe(String name) {
        AppLogger.current().info("upstream health probe", LogField.code("upstream", name, UPSTREAMS));
        var client = clients.stream().filter(c -> Objects.equals(name, c.name())).findFirst().orElseThrow();
        return Map.of("service", "gateway-api", "thread", Thread.currentThread().toString(), "upstream", Map.of(name, client.describe()));
    }
    public record Health(String status, String service, Map<String, Object> upstreams) {}
    public Health databaseHealth() {
        AppLogger.current().info("database health probe");
        Map<String, Object> results = new LinkedHashMap<>();
        boolean healthy = true;
        for (var client : clients.stream().sorted(Comparator.comparing(UpstreamClient::name, Comparator.nullsLast(Comparator.naturalOrder()))).toList()) {
            Map<String, Object> result;
            // 실패한 대상의 호스트·상태는 바로 앞 upstream 호출 로그에 있다. 예외 메시지에는 호출 URI가 들어 있어 남기지 않는다
            try { result = client.databaseHealth(); }
            catch (UpstreamException failure) {
                AppLogger.current().warn("database health probe failed", LogField.errorType("upstream_unavailable"));
                result = Map.of("status", "error", "error", "upstream_unavailable");
            }
            if (result == null) {
                AppLogger.current().warn("database health probe returned no body", LogField.errorType("empty_response"));
                result = Map.of("status", "error", "error", "empty_response");
            }
            results.put(client.name(), result);
            healthy &= "ok".equals(result.get("status"));
        }
        AppLogger.current().info("database health probe completed", LogField.result("healthy", healthy), LogField.result("upstreams", results));
        return new Health(healthy ? "ok" : "degraded", "gateway-api", results);
    }
}
