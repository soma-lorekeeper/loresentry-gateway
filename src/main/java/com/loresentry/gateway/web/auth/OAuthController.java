package com.loresentry.gateway.web.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.loresentry.gateway.application.LoginService;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.config.CookieSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        } catch(IllegalArgumentException invalid) {return result(LoginService.Result.FAILED,List.of());}
    }
    @GetMapping("/auth/oauth/google/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request) {
        LoginService.CallbackResult callback;
        try {
            callback=login.callback(AuthCookies.single(request,names.oauthName()),single(request,"state"),single(request,"code"),single(request,"error"));
        } catch(IllegalArgumentException ambiguous) {return result(LoginService.Result.INVALID,List.of());}
        var output=new ArrayList<ResponseCookie>();
        var result=callback.result();
        if(result==LoginService.Result.SUCCESS) {
            try {output.add(cookies.session(callback.session().id().value(),callback.session().expiresAt()));output.addAll(cookies.clearLegacy(request));}
            catch(IllegalArgumentException invalid) {result=LoginService.Result.FAILED;}
        }
        if(Boolean.TRUE.equals(callback.consumed())) output.add(cookies.clearOAuth());
        return result(result,output);
    }
    @RequestMapping(value={"/auth/oauth/google/prepare","/auth/oauth/google/callback"},method=RequestMethod.HEAD)
    public ResponseEntity<Void> head() {return ResponseEntity.status(405).header("Allow","GET").build();}
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
