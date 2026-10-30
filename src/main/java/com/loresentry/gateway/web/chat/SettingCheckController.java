package com.loresentry.gateway.web.chat;

import java.util.UUID;
import com.loresentry.gateway.application.SettingCheckService;
import com.loresentry.gateway.security.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettingCheckController {
    private final SettingCheckService service;
    public SettingCheckController(SettingCheckService service) { this.service = service; }

    @PostMapping("/projects/{projectId}/setting-checks")
    public ResponseEntity<SettingCheckDtos.Run> start(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody SettingCheckDtos.Start body) {
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Run.from(service.start(userId, projectId, body == null ? null : body.internal())));
    }
    @GetMapping("/projects/{projectId}/setting-checks/summary")
    public ResponseEntity<SettingCheckDtos.Summary> summary(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Summary.from(service.summary(userId, projectId)));
    }
    @GetMapping("/projects/{projectId}/setting-checks")
    public ResponseEntity<SettingCheckDtos.Runs> runs(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestParam(name = "document_id") UUID documentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Runs.from(service.runs(userId, projectId, documentId)));
    }
    @GetMapping("/projects/{projectId}/setting-checks/verdicts")
    public ResponseEntity<SettingCheckDtos.Decisions> verdicts(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Decisions.from(service.verdicts(userId, projectId)));
    }
    @GetMapping("/setting-checks/{runId}")
    public ResponseEntity<SettingCheckDtos.Run> run(@CurrentUser UUID userId, @PathVariable UUID runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Run.withFindings(service.run(userId, runId)));
    }
    @PostMapping("/setting-checks/{runId}/cancel")
    public ResponseEntity<Void> cancel(@CurrentUser UUID userId, @PathVariable UUID runId) {
        service.cancel(userId, runId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PutMapping("/setting-check-findings/{findingId}/verdict")
    public ResponseEntity<SettingCheckDtos.Finding> setVerdict(@CurrentUser UUID userId, @PathVariable UUID findingId, @RequestBody SettingCheckDtos.Verdict body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Finding.from(service.setVerdict(userId, findingId, body == null ? null : body.internal())));
    }
    @DeleteMapping("/setting-check-findings/{findingId}/verdict")
    public ResponseEntity<SettingCheckDtos.Finding> clearVerdict(@CurrentUser UUID userId, @PathVariable UUID findingId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Finding.from(service.clearVerdict(userId, findingId)));
    }
}
