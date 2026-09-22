package com.iiot.auth;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.iiot.auth.AuthRepository.*;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    public record NewUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
                          @NotBlank @Email @Size(max = 254) String email,
                          @NotBlank @Size(min = 12, max = 72) String password,
                          @NotNull Role role) {
        @Override
        public String toString() {
            return "NewUser[redacted]";
        }
    }

    public record RoleRequest(@NotNull Role role) {
    }

    public record StatusRequest(@NotNull Boolean enabled) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody NewUser request) {
        return users.create(request);
    }

    @GetMapping
    public List<UserView> list(@RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
                               @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return users.list(limit, offset);
    }

    @PutMapping("/{id}/role")
    public UserView role(Authentication actor, @PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        return users.update(UUID.fromString(actor.getName()), id, request.role(), null);
    }

    @PutMapping("/{id}/status")
    public UserView status(Authentication actor, @PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return users.update(UUID.fromString(actor.getName()), id, null, request.enabled());
    }
}
