package com.loresentry.gateway.web;
import java.util.Map;
import com.loresentry.gateway.application.ProbeService;
import com.loresentry.gateway.logging.AppLogger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class AuthController {
    private final ProbeService probes;
    public AuthController(ProbeService probes) { this.probes = probes; }
    @GetMapping("/auth")
    public Map<String, Object> describe() { AppLogger.current().info("auth health probe requested"); return probes.describe("authentication"); }
}
