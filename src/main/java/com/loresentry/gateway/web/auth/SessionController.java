package com.loresentry.gateway.web.auth;

import com.loresentry.gateway.application.SessionService;
import com.loresentry.gateway.config.CookieSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SessionController {
    private final SessionService sessions;
    private final AuthCookies cookies;
    private final CookieSettings names;
    public SessionController(SessionService sessions,AuthCookies cookies,CookieSettings names) {this.sessions=sessions;this.cookies=cookies;this.names=names;}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RevocationResponse(@com.fasterxml.jackson.annotation.JsonProperty("session_revocation") String revocation,
            String code,String message,@com.fasterxml.jackson.annotation.JsonProperty("next_action") String nextAction) {}
    @PostMapping("/auth/sessions/revoke")
    public ResponseEntity<RevocationResponse> revoke(HttpServletRequest request) {
        SessionService.Revocation result;
        try {result=sessions.revoke(AuthCookies.single(request,names.sessionName()));}
        catch(IllegalArgumentException ambiguous) {result=SessionService.Revocation.REJECTED;}
        catch(RuntimeException unconfirmed) {result=SessionService.Revocation.UNCONFIRMED;}
        int status=switch(result) {case CONFIRMED,NOT_REQUESTED -> 200;case REJECTED -> 400;case UNCONFIRMED -> 503;};
        String code=switch(result) {case REJECTED -> "INVALID_SESSION_ID";case UNCONFIRMED -> "REVOCATION_UNCONFIRMED";default -> null;};
        var body=new RevocationResponse(result.name().toLowerCase(java.util.Locale.ROOT),code,
                code==null?null:"Server session revocation could not be confirmed.",code==null?null:"NONE");
        var response=ResponseEntity.status(status).header("Cache-Control","no-store");
        response.header("Set-Cookie",cookies.clearSession().toString());
        return response.body(body);
    }
}
