package com.goldman.alerting.control.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class SessionRequestFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getServletPath().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            // Both UIs proxy /api through their own origin. Reject browser writes from other sites or sibling origins.
            var site = request.getHeader("Sec-Fetch-Site");
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                    && ("cross-site".equals(site) || "same-site".equals(site))) {
                response.sendError(403);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
