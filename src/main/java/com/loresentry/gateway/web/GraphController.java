package com.loresentry.gateway.web;
import java.util.Map;
import com.loresentry.gateway.application.ProbeService;
import com.loresentry.gateway.logging.AppLogger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class GraphController {
    private final ProbeService probes;
    public GraphController(ProbeService probes) { this.probes = probes; }
    @GetMapping("/graph")
    public Map<String, Object> describe() { AppLogger.current().info("graph health probe requested"); return probes.describe("graph-rag"); }
}
