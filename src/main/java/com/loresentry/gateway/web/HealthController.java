package com.loresentry.gateway.web;

import java.util.Map;

import com.loresentry.gateway.logging.AppLogger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        AppLogger.current().info("health requested");
        return Map.of("status", "ok");
    }

    @GetMapping("/")
    public Map<String, String> root() {
        AppLogger.current().info("root requested");
        return Map.of("service", "gateway-api");
    }
}
