package com.loresentry.gateway.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class SensitiveResponseFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        if (request.getRequestURI().startsWith("/auth/")) response.setHeader("Cache-Control","no-store");
        if (request.getRequestURI().startsWith("/auth/oauth/")) response.setHeader("Referrer-Policy","no-referrer");
        chain.doFilter(request,response);
    }
}
