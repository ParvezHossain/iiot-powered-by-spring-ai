package com.iiot.auth;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class AuthRateLimitFilterTests {
    @Test
    void limitsPeerIgnoresForwardedHeadersAndPreservesBusinessRequests() throws Exception {
        var filter = new AuthRateLimitFilter(2, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            var request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setServletPath("/api/auth/login");
            request.addHeader("X-Forwarded-For", "different-" + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (q, r) -> calls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(i < 2 ? 200 : 429);
            if (i == 2) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        }
        var request = new MockHttpServletRequest("GET", "/api/anomalies");
        request.setServletPath("/api/anomalies");
        filter.doFilter(request, new MockHttpServletResponse(), (q, r) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(3);
    }
}
