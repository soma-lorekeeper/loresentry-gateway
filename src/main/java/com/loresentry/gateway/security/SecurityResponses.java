package com.loresentry.gateway.security;

import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class SecurityResponses {
    private SecurityResponses() {}
    public static void write(HttpServletResponse response, SecurityFailure.Reason reason) throws IOException {
        // CSRF·세션 필터와 예외 처리기가 막은 이유를 결과 줄(request completed)에 맡긴다. 고정 enum 이름뿐이라 요청 값이 섞이지 않는다
        AppLogger.current().addToOutcome(LogField.errorType(reason.name()));
        response.setStatus(reason.status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        // All values come from the fixed enum, never from request data or exceptions.
        // trace_id는 추적 라이브러리가 만든 32자리 hex라 그대로 이어 붙여도 안전하다(LOG_SCHEMA.md 7절)
        String traceId=AppLogger.current().traceId();
        response.getWriter().write("{\"code\":\""+reason.name()+"\",\"message\":\""+reason.message+
                "\",\"next_action\":\""+reason.nextAction+"\""+(traceId==null?"":",\"trace_id\":\""+traceId+"\"")+"}");
    }
}
