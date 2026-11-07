package com.loresentry.gateway.web.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.application.AuthOperationFailure;
import com.loresentry.gateway.application.PasswordLoginService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PasswordController {
    public record SignUp(@JsonProperty("login_id") String loginId,String password) {
        @Override public String toString(){return "SignUp[redacted]";}
    }
    public record Login(@JsonProperty("login_id") String loginId,String password) {
        @Override public String toString(){return "Login[redacted]";}
    }
    public record Status(String status) {}
    private final PasswordLoginService passwords;
    private final AuthCookies cookies;
    public PasswordController(PasswordLoginService passwords,AuthCookies cookies){this.passwords=passwords;this.cookies=cookies;}

    @PostMapping("/auth/password/signup")
    public ResponseEntity<Status> signUp(@RequestBody SignUp body,HttpServletRequest request) {
        if(body==null) throw new AuthOperationFailure(400,"INVALID_REQUEST","NONE");
        return respond(passwords.signUp(body.loginId(),body.password()),request);
    }
    @PostMapping("/auth/password/login")
    public ResponseEntity<Status> login(@RequestBody Login body,HttpServletRequest request) {
        if(body==null) throw new AuthOperationFailure(400,"INVALID_REQUEST","NONE");
        return respond(passwords.login(body.loginId(),body.password()),request);
    }
    private ResponseEntity<Status> respond(PasswordLoginService.Outcome outcome,HttpServletRequest request) {
        List<ResponseCookie> set=new ArrayList<>();
        final String status;
        try {
            if(outcome.authenticated()) {
                set.add(cookies.session(outcome.session().id().value(),outcome.session().expiresAt()));
                set.add(cookies.clearConsent());
                set.addAll(cookies.clearLegacy(request));
                status="AUTHENTICATED";
            } else {
                set.add(cookies.consent(outcome.consent().id().value(),outcome.consent().expiresAt()));
                status="TERMS_REQUIRED";
            }
        } catch(IllegalArgumentException invalid){throw new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");}
        var response=ResponseEntity.ok().header("Cache-Control","no-store");
        set.forEach(cookie->response.header("Set-Cookie",cookie.toString()));
        return response.body(new Status(status));
    }
}
