package com.loresentry.gateway.web.auth;

import com.loresentry.gateway.application.TokenService;
import com.loresentry.gateway.application.AuthOperationFailure;
import com.loresentry.gateway.config.CookieSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TokenController {
    private final TokenService tokens;
    private final AuthCookies cookies;
    private final CookieSettings names;
    public TokenController(TokenService tokens,AuthCookies cookies,CookieSettings names) {this.tokens=tokens;this.cookies=cookies;this.names=names;}
    @PostMapping("/auth/tokens/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request) {
        String token;
        try {token=AuthCookies.single(request,names.refreshName());}
        catch(IllegalArgumentException ambiguous) {throw AuthOperationFailure.refreshRejected();}
        var pair=tokens.refresh(token);
        java.util.List<org.springframework.http.ResponseCookie> output;
        try {output=cookies.authentication(pair);}
        catch(IllegalArgumentException invalid) {throw AuthOperationFailure.refreshUnknown();}
        var response=ResponseEntity.noContent().header("Cache-Control","no-store");
        output.forEach(cookie->response.header("Set-Cookie",cookie.toString()));
        return response.build();
    }
}
