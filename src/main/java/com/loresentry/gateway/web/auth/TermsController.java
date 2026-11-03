package com.loresentry.gateway.web.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loresentry.gateway.client.SupportedLocale;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.application.*;
import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 첫 로그인 때 약관을 보여 주고 동의를 받는 컨트롤러. 동의 쿠키(__Host-ls_consent)로 로그인 중인 사람을 찾는다.
 *
 * <p>각 핸들러의 첫 줄은 진입 로그다. 아직 세션이 없어 user.id도 없고, 동의 요청 ID·약관 버전은 남기지 않는다.
 * locale은 허용 목록 안의 코드만 남긴다.
 */
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
        AppLogger.current().info("terms get requested",LogField.code("locale",locale,SupportedLocale.VALUES));
        try {
            var value=terms.query(credential(request),locale);
            return ResponseEntity.ok().header("Cache-Control","no-store").body(new Terms(value.termsVersionId(),value.version(),value.title(),value.content(),value.effectiveAt(),value.expiresAt(),value.locale()));
        } catch(AuthOperationFailure failure){clearInvalid(response,failure);throw failure;}
    }
    @PostMapping("/auth/terms/accept")
    public ResponseEntity<Void> accept(@RequestBody Accept body,HttpServletRequest request,HttpServletResponse response) {
        AppLogger.current().info("terms accept requested");
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
