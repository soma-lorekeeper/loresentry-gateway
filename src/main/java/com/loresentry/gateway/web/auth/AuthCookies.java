package com.loresentry.gateway.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.loresentry.gateway.config.CookieSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    private final CookieSettings settings;
    private final Clock clock;
    public AuthCookies(CookieSettings settings,Clock clock) { this.settings=settings;this.clock=clock; }
    public ResponseCookie session(String value,Instant expiresAt) {
        new com.loresentry.gateway.application.SessionId(value);
        return cookie(settings.sessionName(),value,remaining(clock.instant(),expiresAt,1209600),"Strict");
    }
    public ResponseCookie clearSession() { return cookie(settings.sessionName(),"",0,"Strict"); }
    public void setSession(HttpServletResponse response,String value,Instant expiresAt) {
        var cookie=session(value,expiresAt);
        response.addHeader("Set-Cookie",cookie.toString());
        response.setHeader("Cache-Control","no-store");
    }
    public ResponseCookie oauth(String requestId,Instant expiresAt) {
        if(requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,256}")) throw invalid();
        return cookie(settings.oauthName(),requestId,remaining(clock.instant(),expiresAt,300),"Lax");
    }
    public ResponseCookie clearOAuth() { return cookie(settings.oauthName(),"",0,"Lax"); }
    // One-time migration cleanup on successful login or CSRF-approved logout only.
    // Match only legacy names for this environment, never the new session cookie.
    public List<ResponseCookie> clearLegacy(HttpServletRequest request) {
        String prefix=settings.secure()?"__Host-":"";
        var legacy=java.util.Set.of(prefix+"ls_at",prefix+"ls_rt");
        var present=new java.util.HashSet<String>();
        if(request.getCookies()!=null) for(var candidate:request.getCookies())
            if(legacy.contains(candidate.getName())) present.add(candidate.getName());
        return present.stream().sorted().map(name->cookie(name,"",0,"Strict")).toList();
    }
    public static String single(HttpServletRequest request,String name) {
        String found=null;
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(name.equals(cookie.getName())) {
            if(found!=null) throw new IllegalArgumentException("Ambiguous cookie");
            found=cookie.getValue();
        }
        return found;
    }
    private ResponseCookie cookie(String name,String value,long maxAge,String sameSite) {
        return ResponseCookie.from(name,value).httpOnly(true).secure(settings.secure()).path("/")
                .sameSite(sameSite).maxAge(maxAge).build();
    }
    private static long remaining(Instant now,Instant expiry,long maximum) {
        if(expiry==null) throw invalid();
        long seconds=Duration.between(now,expiry).getSeconds();
        if(seconds<=0) throw invalid();
        return Math.min(seconds,maximum);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid authentication cookie result"); }
}
