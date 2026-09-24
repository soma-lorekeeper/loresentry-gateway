package com.loresentry.gateway.security;

import java.util.Collections;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class CsrfFilter extends OncePerRequestFilter {
    private static final Set<String> WRITES=Set.of("POST","PUT","PATCH","DELETE");
    private final CorsConfiguration cors;
    public CsrfFilter(CorsConfiguration cors) { this.cors=cors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // OPTIONS reaches CORS before any authentication; only write methods mutate state.
        return !WRITES.contains(request.getMethod());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        var origins=Collections.list(request.getHeaders("Origin"));
        var csrf=Collections.list(request.getHeaders("X-LS-CSRF"));
        boolean allowed=origins.size()==1 && cors.checkOrigin(origins.getFirst())!=null;
        if (!allowed || csrf.size()!=1 || !"1".equals(csrf.getFirst())) {
            response.addHeader("Vary","Origin");
            if(allowed) {
                response.setHeader("Access-Control-Allow-Origin",origins.getFirst());
                response.setHeader("Access-Control-Allow-Credentials","true");
            }
            SecurityResponses.write(response,SecurityFailure.Reason.CSRF_REJECTED);
            return;
        }
        chain.doFilter(request,response);
    }
}
