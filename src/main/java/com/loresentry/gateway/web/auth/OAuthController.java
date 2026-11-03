package com.loresentry.gateway.web.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.loresentry.gateway.application.LoginService;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
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
        URI destination;
        try {
            destination=URI.create(result.authorizationUrl());
            if(!"https".equals(destination.getScheme())||!"accounts.google.com".equals(destination.getHost())
                    ||!"/o/oauth2/v2/auth".equals(destination.getPath())||destination.getUserInfo()!=null
                    ||destination.getPort()!=-1||destination.getFragment()!=null) throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid) {
            // auth가 돌려준 인가 주소가 Google 주소 규칙에 맞지 않는다. 주소 값은 남기지 않는다(state가 들어 있다)
            AppLogger.current().warn("oauth login prepare rejected: unexpected authorization url");
            return result(LoginService.Result.FAILED,List.of());
        }
        try {
            var cookie=cookies.oauth(result.requestId(),result.expiresAt());
            return ResponseEntity.status(302).location(destination).header("Cache-Control","no-store")
                    .header("Referrer-Policy","no-referrer").header("Set-Cookie",cookie.toString()).build();
        } catch(IllegalArgumentException invalid) {
            // 로그인 요청 ID나 만료 시각이 쿠키 규칙에 맞지 않는다. 로그인 요청 ID는 비밀 값이라 남기지 않는다
            AppLogger.current().warn("oauth login prepare failed: login request cookie rejected",LogField.errorType(invalid.getClass().getName()));
            return result(LoginService.Result.FAILED,List.of());
        }
    }
    @GetMapping("/auth/oauth/google/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request) {
        AppLogger.current().info("oauth login callback requested");
        LoginService.CallbackResult callback;
        try {
            callback=login.callback(AuthCookies.single(request,names.oauthName()),single(request,"state"),single(request,"code"),single(request,"error"));
        } catch(IllegalArgumentException ambiguous) {
            // 로그인 요청 쿠키나 state·code·error 파라미터가 둘 이상이다. 값은 로그인 과정의 비밀 값이라 남기지 않는다
            AppLogger.current().warn("oauth login callback rejected: ambiguous parameters");
            return result(LoginService.Result.INVALID,List.of());
        }
        var output=new ArrayList<ResponseCookie>();
        var result=callback.result();
        if(result==LoginService.Result.SUCCESS) {
            try {output.add(cookies.session(callback.session().id().value(),callback.session().expiresAt()));output.add(cookies.clearConsent());output.addAll(cookies.clearLegacy(request));}
            catch(IllegalArgumentException invalid) {
                // auth가 준 세션 ID·만료 시각으로 세션 쿠키를 만들 수 없다. 세션 ID는 인증 수단이라 남기지 않는다
                AppLogger.current().warn("oauth login callback failed: session cookie rejected",LogField.errorType(invalid.getClass().getName()));
                result=LoginService.Result.FAILED;
            }
        }
        if(result==LoginService.Result.TERMS_REQUIRED) {
            try {output.add(cookies.consent(callback.consent().id().value(),callback.consent().expiresAt()));}
            catch(IllegalArgumentException invalid) {
                // 동의 요청 ID·만료 시각으로 동의 쿠키를 만들 수 없다. 동의 요청 ID는 남기지 않는다
                AppLogger.current().warn("oauth login callback failed: consent cookie rejected",LogField.errorType(invalid.getClass().getName()));
                result=LoginService.Result.FAILED;
            }
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
        // 303 결과 줄(request completed)에 로그인 결과(SUCCESS·TERMS_REQUIRED·CANCELLED·INVALID·UNAVAILABLE·FAILED)를 붙인다
        AppLogger.current().addToOutcome(LogField.result("login_result",result));
        var response=ResponseEntity.status(303).location(URI.create(returnAddress+"?result="+result.name().toLowerCase(Locale.ROOT)))
                .header("Cache-Control","no-store").header("Referrer-Policy","no-referrer");
        cookies.forEach(cookie->response.header("Set-Cookie",cookie.toString()));
        return response.build();
    }
}
