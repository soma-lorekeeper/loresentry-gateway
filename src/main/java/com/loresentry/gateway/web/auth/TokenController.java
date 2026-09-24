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
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RevocationResponse(@com.fasterxml.jackson.annotation.JsonProperty("refresh_revocation") String revocation,
            String code,String message,@com.fasterxml.jackson.annotation.JsonProperty("next_action") String nextAction) {}
    @PostMapping("/auth/tokens/revoke")
    public ResponseEntity<RevocationResponse> revoke(HttpServletRequest request) {
        TokenService.Revocation result;
        try {result=tokens.revoke(AuthCookies.single(request,names.refreshName()));}
        catch(RuntimeException unconfirmed) {result=TokenService.Revocation.UNCONFIRMED;}
        int status=switch(result) {case CONFIRMED,NOT_REQUESTED -> 200;case REJECTED -> 401;case UNCONFIRMED -> 503;};
        String code=switch(result) {case REJECTED -> "INVALID_REFRESH_TOKEN";case UNCONFIRMED -> "REVOCATION_UNCONFIRMED";default -> null;};
        var body=new RevocationResponse(result.name().toLowerCase(java.util.Locale.ROOT),code,
                code==null?null:"Server session revocation could not be confirmed.",code==null?null:"NONE");
        var response=ResponseEntity.status(status).header("Cache-Control","no-store");
        cookies.clearAuthentication().forEach(cookie->response.header("Set-Cookie",cookie.toString()));
        return response.body(body);
    }
}
