package com.iiot.auth;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import static com.iiot.auth.AuthRepository.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthRepository users;

    public AuthController(AuthService auth, AuthRepository users) {
        this.auth = auth;
        this.users = users;
    }

    public record Registration(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
                               @NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(min = 12, max = 72) String password,
                               @Null(message = "Role cannot be supplied during registration") String role,
                               @Null(message = "Roles cannot be supplied during registration") Object roles) {
        @Override
        public String toString() {
            return "Registration[redacted]";
        }
    }

    public record Login(@NotBlank @Size(max = 254) String usernameOrEmail, @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    public record RefreshRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[redacted]";
        }
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    public UserView register(@Valid @RequestBody Registration request) {
        return auth.create(request.username(), request.email(), request.password(), Role.USER);
    }

    @PostMapping("/login")
    @SecurityRequirements
    public AuthService.Tokens login(@Valid @RequestBody Login request) {
        return auth.login(request.usernameOrEmail(), request.password());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public AuthService.Tokens refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirements
    public void logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        return users.find(UUID.fromString(authentication.getName()), false).view();
    }
}
