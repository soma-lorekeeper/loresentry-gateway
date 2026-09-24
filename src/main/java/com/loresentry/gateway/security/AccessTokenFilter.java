package com.loresentry.gateway.security;

import java.util.Collections;
import java.util.Enumeration;
import com.loresentry.gateway.config.CookieSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class AccessTokenFilter extends OncePerRequestFilter {
    public static final String USER_ATTRIBUTE=AccessTokenFilter.class.getName()+".user";
    private final AccessTokenVerifier verifier;
    private final CookieSettings cookies;
    public AccessTokenFilter(AccessTokenVerifier verifier,CookieSettings cookies) { this.verifier=verifier;this.cookies=cookies; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String method=request.getMethod(),path=request.getRequestURI();
        return (("GET".equals(method)||"HEAD".equals(method))&&"/health".equals(path))
                || ("GET".equals(method) && ("/auth/oauth/google/prepare".equals(path)||"/auth/oauth/google/callback".equals(path)))
                || ("POST".equals(method) && ("/auth/tokens/refresh".equals(path)||"/auth/tokens/revoke".equals(path)));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        AccessTokenVerifier.Claims claims;
        try {
            String token=null;
            if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookies.accessName().equals(cookie.getName())) {
                if(token!=null) throw new SecurityFailure(SecurityFailure.Reason.ACCESS_TOKEN_INVALID);
                token=cookie.getValue();
            }
            claims=verifier.verify(token);
        } catch(SecurityFailure failure) { SecurityResponses.write(response,failure.reason());return; }
        var sanitized=new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) { return "X-User-Id".equalsIgnoreCase(name)?null:super.getHeader(name); }
            @Override public Enumeration<String> getHeaders(String name) { return "X-User-Id".equalsIgnoreCase(name)?Collections.emptyEnumeration():super.getHeaders(name); }
            @Override public Enumeration<String> getHeaderNames() { return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n->!"X-User-Id".equalsIgnoreCase(n)).toList()); }
        };
        sanitized.setAttribute(USER_ATTRIBUTE,claims.userId());
        chain.doFilter(sanitized,response);
    }
}
