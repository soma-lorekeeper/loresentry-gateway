package com.loresentry.gateway.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class SecurityResponses {
    private SecurityResponses() {}
    public static void write(HttpServletResponse response, SecurityFailure.Reason reason) throws IOException {
        response.setStatus(reason.status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        // All values come from the fixed enum, never from request data or exceptions.
        response.getWriter().write("{\"code\":\""+reason.name()+"\",\"message\":\""+reason.message+
                "\",\"next_action\":\""+reason.nextAction+"\"}");
    }
}
