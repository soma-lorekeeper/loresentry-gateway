package com.loresentry.gateway.web.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.loresentry.gateway.application.LoginService;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.logging.AppLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Google 로그인의 시작(prepare)과 돌아오기(callback)를 처리하는 컨트롤러.
 *
 * <p>각 핸들러의 첫 줄은 진입 로그다. OAuth code·state·error, 로그인 요청 쿠키 값은 로그인 과정의 비밀 값이라 남기지 않는다.
 */
@RestController
public class OAuthController {
    private final LoginService login;
    private final AuthCookies cookies;
    private final CookieSettings names;
    private final String returnAddress;
    public OAuthController(LoginService login,AuthCookies cookies,CookieSettings names,BrowserProperties browser) {
        this.login=login;this.cookies=cookies;this.names=names;this.returnAddress=browser.loginRedirect();
    }
    @GetMapping("/auth/oauth/google/prepare")
    public ResponseEntity<Void> prepare() {
        AppLogger.current().info("oauth login prepare requested");
        var result=login.prepare();
        if(result.result()!=LoginService.Result.SUCCESS) return result(result.result(),List.of());
        try {
            var destination=URI.create(result.authorizationUrl());
            if(!"https".equals(destination.getScheme())||!"accounts.google.com".equals(destination.getHost())
                    ||!"/o/oauth2/v2/auth".equals(destination.getPath())||destination.getUserInfo()!=null
                    ||destination.getPort()!=-1||destination.getFragment()!=null) throw new IllegalArgumentException();
            var cookie=cookies.oauth(result.requestId(),result.expiresAt());
            return ResponseEntity.status(302).location(destination).header("Cache-Control","no-store")
                    .header("Referrer-Policy","no-referrer").header("Set-Cookie",cookie.toString()).build();
        } catch(IllegalArgumentException invalid) {
            // auth가 돌려준 인가 주소가 Google 주소 규칙에 맞지 않는다. 주소 값은 남기지 않는다(state가 들어 있다)
            AppLogger.current().warn("oauth login prepare rejected: unexpected authorization url");
            return result(LoginService.Result.FAILED,List.of());
        }
    }
    @GetMapping("/auth/oauth/google/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request) {
        AppLogger.current().info("oauth login callback requested");
        LoginService.CallbackResult callback;
        try {
            callback=login.callback(AuthCookies.single(request,names.oauthName()),single(request,"state"),single(request,"code"),single(request,"error"));
        } catch(IllegalArgumentException ambiguous) {return result(LoginService.Result.INVALID,List.of());}
        var output=new ArrayList<ResponseCookie>();
        var result=callback.result();
        if(result==LoginService.Result.SUCCESS) {
            try {output.add(cookies.session(callback.session().id().value(),callback.session().expiresAt()));output.add(cookies.clearConsent());output.addAll(cookies.clearLegacy(request));}
            catch(IllegalArgumentException invalid) {result=LoginService.Result.FAILED;}
        }
        if(result==LoginService.Result.TERMS_REQUIRED) {
            try {output.add(cookies.consent(callback.consent().id().value(),callback.consent().expiresAt()));}
            catch(IllegalArgumentException invalid) {result=LoginService.Result.FAILED;}
        }
        if(Boolean.TRUE.equals(callback.consumed())) output.add(cookies.clearOAuth());
        return result(result,output);
    }
    @RequestMapping(value={"/auth/oauth/google/prepare","/auth/oauth/google/callback"},method=RequestMethod.HEAD)
    public ResponseEntity<Void> head() {AppLogger.current().info("oauth head requested");return ResponseEntity.status(405).header("Allow","GET").build();}
    private static String single(HttpServletRequest request,String name) {
        var values=request.getParameterValues(name);
        if(values==null)return null;
        if(values.length!=1)throw new IllegalArgumentException("Ambiguous OAuth input");
        return values[0];
    }
    private ResponseEntity<Void> result(LoginService.Result result,List<ResponseCookie> cookies) {
        var response=ResponseEntity.status(303).location(URI.create(returnAddress+"?result="+result.name().toLowerCase(Locale.ROOT)))
                .header("Cache-Control","no-store").header("Referrer-Policy","no-referrer");
        cookies.forEach(cookie->response.header("Set-Cookie",cookie.toString()));
        return response.build();
    }
}
