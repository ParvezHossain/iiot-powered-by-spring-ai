package com.iiot.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.iiot.auth.AuthRepository.*;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserService {
    private final AuthRepository users;
    private final AuthService auth;

    public AdminUserService(AuthRepository users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    public UserView create(AdminUserController.NewUser r) {
        return auth.create(r.username(), r.email(), r.password(), r.role());
    }

    public List<UserView> list(int limit, int offset) {
        return users.list(limit, offset);
    }

    @Transactional
    public UserView update(UUID actor, UUID id, Role role, Boolean enabled) {
        // Prevent admins from accidentally removing their own administrative access.
        if (actor.equals(id) && (role == Role.USER || Boolean.FALSE.equals(enabled)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove your own administrative access");
        users.jdbc().queryForObject("SELECT id FROM telemetry.auth_bootstrap_lock WHERE id=1 FOR UPDATE", Integer.class);
        var user = users.find(id, true);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        if (user.role() == Role.ADMIN && user.enabled() && (role == Role.USER || Boolean.FALSE.equals(enabled))
                && users.jdbc().queryForObject("SELECT COUNT(*) FROM telemetry.auth_users WHERE role='ADMIN' AND enabled=TRUE", Integer.class) <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the last enabled administrator");
        users.jdbc().update("UPDATE telemetry.auth_users SET role=?,enabled=? WHERE id=?",
                role == null ? user.role().name() : role.name(), enabled == null ? user.enabled() : enabled, id);
        users.revoke(id);
        AuthService.audit("user_updated_by_" + actor, id);
        return users.find(id, false).view();
    }
}
