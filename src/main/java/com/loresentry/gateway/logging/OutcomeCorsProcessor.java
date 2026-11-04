package com.loresentry.gateway.logging;

import java.io.IOException;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Spring 기본 CORS 처리기와 같고, 허용하지 않는 Origin·메서드·헤더를 403으로 거절할 때 그 이유({@code CORS_REJECTED})를
 * 결과 줄(request completed)에 맡긴다(LOG_SCHEMA.md 5절 "실패의 결과는 이 한 줄이다"). 따로 줄을 찍지 않고, 응답은 기본 처리기 그대로다.
 * Origin 값은 브라우저가 보낸 문자열이라 남기지 않는다. BrowserCorsConfiguration이 CORS 필터에 붙인다.
 */
public final class OutcomeCorsProcessor extends DefaultCorsProcessor {
    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        AppLogger.current().addToOutcome(LogField.errorType("CORS_REJECTED"));
        super.rejectRequest(response);
    }
}
