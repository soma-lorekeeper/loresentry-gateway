package com.loresentry.gateway.web;
import java.util.Map;
import com.loresentry.gateway.application.ProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class AiChatController {
    private final ProbeService probes;
    public AiChatController(ProbeService probes) { this.probes = probes; }
    @GetMapping("/ai-chat")
    public Map<String, Object> describe() { return probes.describe("ai-chat"); }
}
