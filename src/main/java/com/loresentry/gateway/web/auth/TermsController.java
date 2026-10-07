package com.loresentry.gateway.web.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.application.*;
import com.loresentry.gateway.config.CookieSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public final class TermsController {
    public record Accept(@JsonProperty("terms_version_id") String termsVersionId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Terms(@JsonProperty("terms_version_id") String termsVersionId,String version,String title,String content,
            @JsonProperty("effective_at") Instant effectiveAt,@JsonProperty("expires_at") Instant expiresAt,String locale) {}
    private final TermsService terms;
    private final AuthCookies cookies;
    private final CookieSettings names;
    public TermsController(TermsService terms,AuthCookies cookies,CookieSettings names){this.terms=terms;this.cookies=cookies;this.names=names;}

    @GetMapping("/auth/terms")
    public ResponseEntity<Terms> query(@RequestParam(name="locale",required=false) String locale,HttpServletRequest request,HttpServletResponse response) {
        try {
            var value=terms.query(credential(request),locale);
            return ResponseEntity.ok().header("Cache-Control","no-store").body(new Terms(value.termsVersionId(),value.version(),value.title(),value.content(),value.effectiveAt(),value.expiresAt(),value.locale()));
        } catch(AuthOperationFailure failure){clearInvalid(response,failure);throw failure;}
    }
    @PostMapping("/auth/terms/accept")
    public ResponseEntity<Void> accept(@RequestBody Accept body,HttpServletRequest request,HttpServletResponse response) {
        try {
            var session=terms.accept(credential(request),body.termsVersionId());
            final org.springframework.http.ResponseCookie sessionCookie;
            try {sessionCookie=cookies.session(session.id().value(),session.expiresAt());}
            catch(IllegalArgumentException invalid){throw new AuthOperationFailure(502,"UPSTREAM_INVALID_RESPONSE","RESTART_LOGIN");}
            return ResponseEntity.noContent().header("Cache-Control","no-store")
                    .header("Set-Cookie",sessionCookie.toString(),cookies.clearConsent().toString()).build();
        } catch(AuthOperationFailure failure){clearInvalid(response,failure);throw failure;}
    }
    private ConsentId credential(HttpServletRequest request) {
        try {return new ConsentId(AuthCookies.single(request,names.consentName()));}
        catch(IllegalArgumentException invalid){throw new AuthOperationFailure(401,"CONSENT_REQUEST_INVALID","RESTART_LOGIN");}
    }
    private void clearInvalid(HttpServletResponse response,AuthOperationFailure failure) {
        if("CONSENT_REQUEST_INVALID".equals(failure.code()))response.addHeader("Set-Cookie",cookies.clearConsent().toString());
    }
}
