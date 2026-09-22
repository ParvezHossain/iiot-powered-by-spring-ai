package com.iiot.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.iiot.auth.AuthRepository.*;

@Service
public class AuthService {
    private final AuthRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuthProperties p;
    private final String dummyHash;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthService(AuthRepository users, PasswordEncoder passwords, TokenService tokens, AuthProperties p) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.p = p;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record Tokens(String accessToken, String tokenType, long expiresIn, String refreshToken,
                         OffsetDateTime refreshExpiresAt, UserView user) {
        @Override
        public String toString() {
            return "Tokens[redacted]";
        }
    }

    static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    static void checkPassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72 || password.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain at least 12 characters and at most 72 UTF-8 bytes");
    }

    @Transactional
    public UserView create(String username, String email, String password, Role role) {
        checkPassword(password);
        UUID id = UUID.randomUUID();
        users.jdbc().update("INSERT INTO telemetry.auth_users(id,username,email,password_hash,role) VALUES(?,?,?,?,?)",
                id, normalize(username), normalize(email), passwords.encode(password), role.name());
        audit("user_created", id);
        return users.find(id, false).view();
    }

    // Failed attempts and detected replay revocations must commit even though the request returns 401.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Tokens login(String login, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw unauthorized();
        var user = users.byLogin(normalize(login));
        boolean valid = passwords.matches(password, user == null ? dummyHash : user.passwordHash());
        var now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (user == null) {
            audit("login_failed", null);
            throw unauthorized();
        }
        if (!user.enabled() || user.lockedUntil() != null && user.lockedUntil().isAfter(now)) throw unauthorized();
        if (!valid) {
            int failures = user.lockedUntil() != null ? 1 : user.failures() + 1;
            users.jdbc().update("UPDATE telemetry.auth_users SET failed_logins=?,locked_until=? WHERE id=?", failures,
                    failures >= p.maxLoginAttempts() ? now.plus(p.lockout()) : null, user.id());
            audit("login_failed", user.id());
            throw unauthorized();
        }
        users.jdbc().update("UPDATE telemetry.auth_users SET failed_logins=0,locked_until=NULL WHERE id=?", user.id());
        audit("login_success", user.id());
        return issue(user, UUID.randomUUID(), now.plus(p.refreshTtl()));
    }

    private Tokens issue(User user, UUID family, OffsetDateTime expiry) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        users.jdbc().update("INSERT INTO telemetry.auth_refresh_tokens(token_hash,user_id,family_id,expires_at) VALUES(?,?,?,?)", hash(raw), user.id(), family, expiry);
        return new Tokens(tokens.access(user), "Bearer", p.accessTtl().toSeconds(), raw, expiry, user.view());
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Tokens refresh(String raw) {
        var token = lookup(raw);
        if (token == null) throw unauthorized();
        var user = users.find(token.userId(), true);
        token = lookup(raw); // Re-read after serializing all session mutations on the user row.
        if (user == null || token == null || !user.enabled() || !token.expiry().isAfter(OffsetDateTime.now())) throw unauthorized();
        if (token.revoked()) {
            users.revoke(user.id());
            audit("refresh_replay", user.id());
            throw unauthorized();
        }
        users.jdbc().update("UPDATE telemetry.auth_refresh_tokens SET revoked=TRUE WHERE token_hash=?", hash(raw));
        return issue(user, token.family(), token.expiry());
    }

    @Transactional
    public void logout(String raw) {
        var token = lookup(raw);
        if (token != null && token.expiry().isAfter(OffsetDateTime.now())) {
            users.find(token.userId(), true);
            token = lookup(raw);
            if (token != null && !token.revoked()) {
                users.revoke(token.userId());
                audit("logout", token.userId());
            }
        }
    }

    record Refresh(UUID userId, UUID family, OffsetDateTime expiry, boolean revoked) {
    }

    private Refresh lookup(String raw) {
        return users.jdbc().query("SELECT * FROM telemetry.auth_refresh_tokens WHERE token_hash=?", (rs, n) ->
                new Refresh(rs.getObject("user_id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getBoolean("revoked")), hash(raw)).stream().findFirst().orElse(null);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials or token");
    }

    static void audit(String event, UUID id) {
        LoggerFactory.getLogger(AuthService.class).info("AUTH event={} userId={}", event, id);
    }
}
