package com.loresentry.gateway.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.loresentry.gateway.logging.AppLogger;
import org.springframework.http.ResponseEntity;

public final class ErrorResponses {

    public record Contract(int status, String message, String nextAction) {
    }

    private ErrorResponses() {
    }

    public static Contract contract(GatewayFailure.Reason reason) {
        return switch (reason) {
            case INVALID_REQUEST -> new Contract(400, "Invalid request.", "NONE");
            case USER_CONTEXT_REQUIRED -> new Contract(401, "User context is required.", "RELOGIN");
            case UPSTREAM_UNAVAILABLE -> new Contract(502, "Upstream service is unavailable.", "RETRY_LATER");
            case INTERNAL_ERROR -> new Contract(500, "An internal error occurred.", "NONE");
        };
    }

    public static ResponseEntity<Map<String, Object>> response(GatewayFailure.Reason reason, String upstream) {
        var contract = contract(reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", reason.name());
        body.put("message", contract.message());
        body.put("next_action", contract.nextAction());
        if (upstream != null) {
            body.put("upstream", upstream);
        }
        // 문의 받은 값을 Kibana에서 바로 찾도록 trace ID를 싣는다(LOG_SCHEMA.md 7절)
        String traceId = AppLogger.current().traceId();
        if (traceId != null) {
            body.put("trace_id", traceId);
        }
        return ResponseEntity.status(contract.status()).body(body);
    }
}
