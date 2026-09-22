package com.iiot.auth;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AuthConfigurationTests {
    private AuthProperties properties(String secret, Duration access, Duration refresh, List<String> origins) {
        return new AuthProperties(secret, "issuer", "audience", access, refresh, 12, 5, Duration.ofMinutes(15), origins, "", "", "");
    }

    @Test
    void rejectsMissingMalformedAndShortSigningSecrets() {
        for (String secret : List.of("", "not base64!", "c2hvcnQ=")) {
            var p = properties(secret, Duration.ofMinutes(15), Duration.ofDays(7), List.of());
            assertThatThrownBy(() -> new SecurityConfiguration().jwtKey(p)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("AUTH_JWT_SECRET must be base64 of at least 32 random bytes");
        }
    }

    @Test
    void rejectsUnsafeTtlsAndWildcardCors() {
        assertThatThrownBy(() -> properties("", Duration.ZERO, Duration.ofDays(7), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("", Duration.ofDays(1), Duration.ofDays(7), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("", Duration.ofMinutes(15), Duration.ofDays(91), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("", Duration.ofMinutes(15), Duration.ofDays(7), List.of("*"))).isInstanceOf(IllegalArgumentException.class);
    }
}
