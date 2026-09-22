package com.iiot.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
    private final JdbcTemplate jdbc;

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Role {USER, ADMIN}

    public record User(UUID id, String username, String email, String passwordHash, Role role,
                       boolean enabled, long version, int failures, OffsetDateTime lockedUntil) {
        public UserView view() {
            return new UserView(id, username, email, role, enabled);
        }

        @Override
        public String toString() {
            return "User[id=" + id + "]";
        }
    }

    public record UserView(UUID id, String username, String email, Role role, boolean enabled) {
    }

    static final RowMapper<User> MAPPER = (rs, n) -> new User(rs.getObject("id", UUID.class),
            rs.getString("username"), rs.getString("email"), rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")), rs.getBoolean("enabled"), rs.getLong("token_version"),
            rs.getInt("failed_logins"), rs.getObject("locked_until", OffsetDateTime.class));

    public User find(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM telemetry.auth_users WHERE id=?" + (lock ? " FOR UPDATE" : ""), MAPPER, id)
                .stream().findFirst().orElse(null);
    }

    User byLogin(String login) {
        return jdbc.query("SELECT * FROM telemetry.auth_users WHERE username=? OR email=? FOR UPDATE", MAPPER, login, login)
                .stream().findFirst().orElse(null);
    }

    List<UserView> list(int limit, int offset) {
        return jdbc.query("SELECT * FROM telemetry.auth_users ORDER BY created_at,id LIMIT ? OFFSET ?", MAPPER, limit, offset)
                .stream().map(User::view).toList();
    }

    void revoke(UUID id) {
        jdbc.update("UPDATE telemetry.auth_refresh_tokens SET revoked=TRUE WHERE user_id=?", id);
        jdbc.update("UPDATE telemetry.auth_users SET token_version=token_version+1 WHERE id=?", id);
    }
}
