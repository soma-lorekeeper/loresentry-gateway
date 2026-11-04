package com.loresentry.gateway.web.auth;

import com.loresentry.gateway.application.SessionService;
import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 로그아웃. 세션 쿠키를 지우고 auth에 서버 세션 폐기를 요청한다. 폐기를 확인하지 못해도 쿠키는 늘 지운다.
 *
 * <p>진입 로그에는 세션 ID를 남기지 않는다(인증 수단). 400·503 본문에는 trace_id를 싣고, 실패 이유를 결과 줄(request completed)에 맡긴다.
 */
@RestController
public class SessionController {
    private final SessionService sessions;
    private final AuthCookies cookies;
    private final CookieSettings names;
    public SessionController(SessionService sessions,AuthCookies cookies,CookieSettings names) {this.sessions=sessions;this.cookies=cookies;this.names=names;}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RevocationResponse(@com.fasterxml.jackson.annotation.JsonProperty("session_revocation") String revocation,
            String code,String message,@com.fasterxml.jackson.annotation.JsonProperty("next_action") String nextAction,
            // 실패 본문에만 싣는 trace ID. 문의 받은 값을 Kibana에서 바로 찾는다. 성공 본문과 trace가 없을 때는 null이라 빠진다
            @com.fasterxml.jackson.annotation.JsonProperty("trace_id") String traceId) {}
    @PostMapping("/auth/sessions/revoke")
    public ResponseEntity<RevocationResponse> revoke(HttpServletRequest request) {
        AppLogger.current().info("session revoke requested");
        SessionService.Revocation result;
        try {result=sessions.revoke(AuthCookies.single(request,names.sessionName()));}
        catch(IllegalArgumentException ambiguous) {
            // 같은 이름의 세션 쿠키가 둘 이상이다. 쿠키 값은 인증 수단이라 남기지 않는다
            AppLogger.current().warn("session revoke rejected: duplicate session cookie",LogField.errorType("DUPLICATE_SESSION_COOKIE"));
            result=SessionService.Revocation.REJECTED;
        }
        catch(RuntimeException unconfirmed) {
            // 폐기 요청 중 예상하지 못한 예외. 예외 메시지는 남기지 않고 클래스만 남긴다
            AppLogger.current().warn("session revoke unconfirmed: unexpected error",LogField.errorType(unconfirmed.getClass().getName()),
                    LogField.safeStackTrace(unconfirmed));
            result=SessionService.Revocation.UNCONFIRMED;
        }
        int status=switch(result) {case CONFIRMED,NOT_REQUESTED -> 200;case REJECTED -> 400;case UNCONFIRMED -> 503;};
        String code=switch(result) {case REJECTED -> "INVALID_SESSION_ID";case UNCONFIRMED -> "REVOCATION_UNCONFIRMED";default -> null;};
        // 실패(400·503)만 이유를 결과 줄에 맡긴다. 상태·레벨은 결과 줄이 응답에서 정한다
        if(code!=null) AppLogger.current().addToOutcome(LogField.errorType(code));
        var body=new RevocationResponse(result.name().toLowerCase(java.util.Locale.ROOT),code,
                code==null?null:"Server session revocation could not be confirmed.",code==null?null:"NONE",
                code==null?null:AppLogger.current().traceId());
        var response=ResponseEntity.status(status).header("Cache-Control","no-store");
        response.header("Set-Cookie",cookies.clearSession().toString());
        cookies.clearLegacy(request).forEach(cookie->response.header("Set-Cookie",cookie.toString()));
        return response.body(body);
    }
}
