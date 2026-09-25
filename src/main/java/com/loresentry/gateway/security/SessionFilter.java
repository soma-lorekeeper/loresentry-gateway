package com.loresentry.gateway.security;

import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.web.auth.AuthCookies;
import com.loresentry.gateway.web.auth.SessionCookieResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SessionFilter extends OncePerRequestFilter {
    public static final String USER_ATTRIBUTE=SessionFilter.class.getName()+".user";
    private final OpaqueSessionVerifier verifier;
    private final CookieSettings names;
    private final AuthCookies cookies;
    public SessionFilter(OpaqueSessionVerifier verifier,CookieSettings names,AuthCookies cookies) {
        this.verifier=verifier;this.names=names;this.cookies=cookies;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String method=request.getMethod(),path=request.getRequestURI();
        return CorsUtils.isPreFlightRequest(request)
                || (("GET".equals(method)||"HEAD".equals(method))&&"/health".equals(path))
                || ("GET".equals(method)&&("/auth/oauth/google/prepare".equals(path)||"/auth/oauth/google/callback".equals(path)))
                || ("POST".equals(method)&&"/auth/sessions/revoke".equals(path));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        SessionCookieResponse renewed;
        UUID user;
        try {
            String id;
            try { id=AuthCookies.single(request,names.sessionName()); }
            catch(IllegalArgumentException duplicate) { throw new SecurityFailure(SecurityFailure.Reason.SESSION_INVALID); }
            var verified=verifier.verify(id);
            user=verified.userId();
            renewed=new SessionCookieResponse(response,cookies,id,verified.expiresAt());
        } catch(SecurityFailure failure) { SecurityResponses.write(response,failure.reason());return; }
        catch(RuntimeException unavailable) { SecurityResponses.write(response,SecurityFailure.Reason.SESSION_UNAVAILABLE);return; }
        var sanitized=new HttpServletRequestWrapper(request) {
            private boolean secret(String name) {
                return "X-User-Id".equalsIgnoreCase(name)||"Cookie".equalsIgnoreCase(name)||"Authorization".equalsIgnoreCase(name);
            }
            @Override public String getHeader(String name){return secret(name)?null:super.getHeader(name);}
            @Override public Enumeration<String> getHeaders(String name){return secret(name)?Collections.emptyEnumeration():super.getHeaders(name);}
            @Override public Enumeration<String> getHeaderNames(){return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n->!secret(n)).toList());}
            @Override public Cookie[] getCookies(){return null;}
        };
        sanitized.setAttribute(USER_ATTRIBUTE,user);
        try { chain.doFilter(sanitized,renewed); }
        finally { renewed.finish(); }
    }
}
