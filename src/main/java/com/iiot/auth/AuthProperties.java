package com.iiot.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        String audience,
        Duration accessTtl,
        Duration refreshTtl,
        int bcryptStrength,
        int maxLoginAttempts,
        Duration lockout,
        List<String> corsOrigins,
        String initialAdminUsername,
        String initialAdminEmail,
        String initialAdminPassword
) {
    public AuthProperties {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || accessTtl == null || accessTtl.isNegative() || accessTtl.toSeconds() < 1
                || accessTtl.compareTo(Duration.ofHours(1)) > 0 || refreshTtl == null
                || refreshTtl.compareTo(accessTtl) <= 0 || refreshTtl.compareTo(Duration.ofDays(90)) > 0
                || bcryptStrength < 10 || bcryptStrength > 16 || maxLoginAttempts < 1
                || lockout == null || lockout.toSeconds() < 1) {
            throw new IllegalArgumentException("Invalid authentication configuration");
        }
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        if (corsOrigins.stream().anyMatch(s -> s.contains("*"))) {
            throw new IllegalArgumentException("CORS requires explicit origins");
        }
    }

    @Override
    public String toString() {
        return "AuthProperties[redacted]";
    }
}
