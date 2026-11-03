package com.loresentry.gateway.logging;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인터넷에서 온 trace 헤더를 숨겨, 외부 요청마다 gateway가 새 trace를 시작하게 한다(LOG_SCHEMA.md 7절).
 *
 * <p>Spring의 observation 필터(HIGHEST_PRECEDENCE+1)가 요청 헤더에서 부모 trace를 읽으므로 그보다 먼저(HIGHEST_PRECEDENCE)
 * 둔다. 숨긴 헤더는 뒤의 필터·컨트롤러에도 보이지 않는다. W3C 헤더만 읽도록 설정했으므로(management.tracing.propagation.consume)
 * W3C의 두 헤더와 baggage만 숨긴다.
 */
public final class TraceContextHidingFilter extends OncePerRequestFilter {
    private static final Set<String> HIDDEN = Set.of("traceparent", "tracestate", "baggage");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new Hidden(request), response);
    }

    private static boolean hidden(String name) {
        return name != null && HIDDEN.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 숨길 헤더를 없는 것처럼 보이게 하는 요청 래퍼. */
    private static final class Hidden extends HttpServletRequestWrapper {
        Hidden(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n -> !hidden(n)).toList());
        }
    }
}
