package com.loresentry.gateway.web.chat;

import com.loresentry.gateway.client.chat.SettingCheckData;
import java.util.UUID;
import com.loresentry.gateway.application.SettingCheckService;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.security.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 브라우저의 설정 검사 요청을 ai-chat으로 넘기는 컨트롤러.
 *
 * <p>각 핸들러의 첫 줄은 진입 로그다. 프로젝트·문서·검사 실행·발견 항목 UUID는 전용 필드로 남긴다. 판정(verdict)은 허용 목록 안의
 * 코드만, 판정 사유는 LogField가 가려 길이만 남긴다.
 */
@RestController
public class SettingCheckController {
    private final SettingCheckService service;
    public SettingCheckController(SettingCheckService service) { this.service = service; }

    @PostMapping("/projects/{projectId}/setting-checks")
    public ResponseEntity<SettingCheckDtos.Run> start(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestBody SettingCheckDtos.Start body) {
        AppLogger.current().info("setting check start requested", LogField.projectId(projectId),
                LogField.documentId(body == null ? null : body.documentId()));
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Run.from(service.start(userId, projectId, body == null ? null : body.internal())));
    }
    @GetMapping("/projects/{projectId}/setting-checks/summary")
    public ResponseEntity<SettingCheckDtos.Summary> summary(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("setting check summary get requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Summary.from(service.summary(userId, projectId)));
    }
    @GetMapping("/projects/{projectId}/setting-checks")
    public ResponseEntity<SettingCheckDtos.Runs> runs(@CurrentUser UUID userId, @PathVariable UUID projectId, @RequestParam(name = "document_id") UUID documentId) {
        AppLogger.current().info("setting check list requested", LogField.projectId(projectId), LogField.documentId(documentId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Runs.from(service.runs(userId, projectId, documentId)));
    }
    @GetMapping("/projects/{projectId}/setting-checks/verdicts")
    public ResponseEntity<SettingCheckDtos.Decisions> verdicts(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("setting check verdict list requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Decisions.from(service.verdicts(userId, projectId)));
    }
    @GetMapping("/setting-checks/{runId}")
    public ResponseEntity<SettingCheckDtos.Run> run(@CurrentUser UUID userId, @PathVariable UUID runId) {
        AppLogger.current().info("setting check get requested", LogField.settingCheckRunId(runId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Run.withFindings(service.run(userId, runId)));
    }
    @PostMapping("/setting-checks/{runId}/cancel")
    public ResponseEntity<Void> cancel(@CurrentUser UUID userId, @PathVariable UUID runId) {
        AppLogger.current().info("setting check cancel requested", LogField.settingCheckRunId(runId));
        service.cancel(userId, runId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PutMapping("/setting-check-findings/{findingId}/verdict")
    public ResponseEntity<SettingCheckDtos.Finding> setVerdict(@CurrentUser UUID userId, @PathVariable UUID findingId, @RequestBody SettingCheckDtos.Verdict body) {
        AppLogger.current().info("setting check verdict set requested", LogField.settingCheckFindingId(findingId),
                LogField.code("verdict", body == null ? null : body.verdict(), SettingCheckData.VERDICTS), LogField.param("reason", body == null ? null : body.reason()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Finding.from(service.setVerdict(userId, findingId, body == null ? null : body.internal())));
    }
    @DeleteMapping("/setting-check-findings/{findingId}/verdict")
    public ResponseEntity<SettingCheckDtos.Finding> clearVerdict(@CurrentUser UUID userId, @PathVariable UUID findingId) {
        AppLogger.current().info("setting check verdict clear requested", LogField.settingCheckFindingId(findingId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SettingCheckDtos.Finding.from(service.clearVerdict(userId, findingId)));
    }
}
