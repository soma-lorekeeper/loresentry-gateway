package com.loresentry.gateway.security;

import com.loresentry.gateway.config.CookieSettings;
import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
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
                || ("POST".equals(method)&&"/auth/sessions/revoke".equals(path))
                || ("GET".equals(method)&&"/auth/terms".equals(path))
                || ("POST".equals(method)&&"/auth/terms/accept".equals(path));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        SessionCookieResponse renewed;
        UUID user;
        try {
            String id;
            try { id=AuthCookies.single(request,names.sessionName()); }
            catch(IllegalArgumentException duplicate) {
                // 같은 이름의 세션 쿠키가 둘 이상이다. 쿠키 값은 인증 수단이라 남기지 않는다
                AppLogger.current().warn("session verify rejected: duplicate session cookie",LogField.errorType("DUPLICATE_SESSION_COOKIE"));
                throw new SecurityFailure(SecurityFailure.Reason.SESSION_INVALID);
            }
            var verified=verifier.verify(id);
            user=verified.userId();
            renewed=new SessionCookieResponse(response,cookies,id,verified.expiresAt(),cookies.clearLegacy(request));
        } catch(SecurityFailure failure) { SecurityResponses.write(response,failure.reason());return; }
        catch(RuntimeException unavailable) {
            // 세션 확인·쿠키 갱신 준비 중 예상하지 못한 예외. 예외 메시지는 남기지 않고 클래스만 남긴다
            AppLogger.current().warn("session verify failed: unexpected error",LogField.errorType(unavailable.getClass().getName()),
                    LogField.safeStackTrace(unavailable));
            SecurityResponses.write(response,SecurityFailure.Reason.SESSION_UNAVAILABLE);return;
        }
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
        // 세션을 확인한 뒤부터는 이 요청의 모든 로그 줄(결과 줄, 채팅 스트리밍 스레드의 줄 포함)에 user.id가 붙는다
        AppLogger.current().identify(user);
        try { chain.doFilter(sanitized,renewed); }
        finally { renewed.finish(); }
    }
}
