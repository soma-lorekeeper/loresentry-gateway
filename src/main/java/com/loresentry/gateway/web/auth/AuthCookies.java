package com.loresentry.gateway.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.loresentry.gateway.application.TokenPair;
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
    public List<ResponseCookie> authentication(TokenPair tokens) {
        if(tokens==null) throw invalid();
        token(tokens.accessToken());token(tokens.refreshToken());
        if(tokens.accessToken().equals(tokens.refreshToken())) throw invalid();
        Instant now=clock.instant();
        long access=remaining(now,tokens.accessExpiresAt(),900);
        long refresh=remaining(now,tokens.refreshExpiresAt(),1209600);
        return List.of(cookie(settings.accessName(),tokens.accessToken(),access,"Strict"),
                cookie(settings.refreshName(),tokens.refreshToken(),refresh,"Strict"));
    }
    public ResponseCookie oauth(String requestId,Instant expiresAt) {
        if(requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,256}")) throw invalid();
        return cookie(settings.oauthName(),requestId,remaining(clock.instant(),expiresAt,300),"Lax");
    }
    public List<ResponseCookie> clearAuthentication() {
        return List.of(cookie(settings.accessName(),"",0,"Strict"),cookie(settings.refreshName(),"",0,"Strict"));
    }
    public ResponseCookie clearOAuth() { return cookie(settings.oauthName(),"",0,"Lax"); }
    public void setAuthentication(HttpServletResponse response,TokenPair tokens) {
        // Construct both before adding either header, so invalid pairs preserve existing cookies.
        append(response,authentication(tokens));
    }
    public static void append(HttpServletResponse response,List<ResponseCookie> cookies) {
        cookies.forEach(cookie->response.addHeader("Set-Cookie",cookie.toString()));
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
    private static void token(String value) {
        if(value==null || value.length()>16384 || !value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid authentication cookie result"); }
}
