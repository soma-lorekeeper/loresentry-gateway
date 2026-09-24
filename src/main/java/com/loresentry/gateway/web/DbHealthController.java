package com.loresentry.gateway.web;
import com.loresentry.gateway.application.ProbeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class DbHealthController {
    private final ProbeService probes;
    public DbHealthController(ProbeService probes) { this.probes = probes; }
    @GetMapping("/health/db")
    public ResponseEntity<ProbeService.Health> databaseHealth() {
        var result = probes.databaseHealth();
        return ResponseEntity.status("ok".equals(result.status()) ? 200 : 503).body(result);
    }
}
