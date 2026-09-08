package com.iiot.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** A shared bearer credential scoped to the MCP servlet, checked on every HTTP request. */
final class McpApiKeyFilter implements Filter {
    private final byte[] expected;

    McpApiKeyFilter(String apiKey) {
        if (apiKey == null || apiKey.length() < 32 || apiKey.length() > 256
                || !apiKey.matches("[A-Za-z0-9._~+/-]+={0,2}")) {
            // Do not include the supplied credential in configuration errors.
            throw new IllegalArgumentException("MCP_API_KEY must contain 32–256 bearer-token characters when MCP is enabled");
        }
        expected = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var http = (HttpServletRequest) request;
        var headers = Collections.list(http.getHeaders("Authorization"));
        if (headers.size() == 1 && authenticated(headers.getFirst())) {
            chain.doFilter(request, response);
            return;
        }
        var denied = (HttpServletResponse) response;
        denied.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        denied.setHeader("WWW-Authenticate", "Bearer realm=\"iiot-mcp\"");
        denied.setHeader("Cache-Control", "no-store");
        denied.setContentType("application/json");
        denied.getWriter().write("{\"error\":\"Unauthorized\"}");
    }

    private boolean authenticated(String authorization) {
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return MessageDigest.isEqual(expected, authorization.substring(7).getBytes(StandardCharsets.UTF_8));
    }
}
