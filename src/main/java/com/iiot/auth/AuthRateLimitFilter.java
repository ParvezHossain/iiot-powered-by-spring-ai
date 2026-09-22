package com.iiot.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounded per-process protection. Uses the socket peer, never untrusted forwarded headers.
 */
final class AuthRateLimitFilter extends OncePerRequestFilter {
    private final Map<String, Window> windows = new HashMap<>();
    private final int limit;
    private final Clock clock;

    AuthRateLimitFilter(int limit, Clock clock) {
        if (limit < 1) throw new IllegalArgumentException("Authentication rate limit must be positive");
        this.limit = limit;
        this.clock = clock;
    }

    record Window(long minute, int count) {
    }

    private synchronized boolean allow(String peer) {
        long minute = clock.millis() / 60000;
        windows.values().removeIf(w -> w.minute() != minute);
        Window previous = windows.get(peer);
        if (previous == null && windows.size() >= 10000) return false;
        int count = previous == null ? 1 : previous.count() + 1;
        if (count > limit) return false;
        windows.put(peer, new Window(minute, count));
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && request.getServletPath().startsWith("/api/auth/")
                && !allow(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "60");
            SecurityConfiguration.error(response, 429, "Too many requests");
            return;
        }
        chain.doFilter(request, response);
    }
}
