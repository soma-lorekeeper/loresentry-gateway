package com.loresentry.gateway.web;
import java.util.Map;
import com.loresentry.gateway.application.ProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class ContentController {
    private final ProbeService probes;
    public ContentController(ProbeService probes) { this.probes = probes; }
    @GetMapping("/content")
    public Map<String, Object> describe() { return probes.describe("content"); }
}
