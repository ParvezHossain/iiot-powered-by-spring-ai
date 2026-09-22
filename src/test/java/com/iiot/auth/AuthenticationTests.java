package com.iiot.auth;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"simulator.enabled=false", "alerts.enabled=false",
        "spring.datasource.url=${AUTH_TEST_DATABASE_URL:jdbc:h2:mem:auth-tests;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "auth.cors-origins=http://localhost:4200"})
@AutoConfigureMockMvc
class AuthenticationTests {
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    InitialAdminInitializer bootstrap;
    @Autowired
    AuthProperties properties;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    JwtEncoder encoder;
    @Autowired
    AuthService service;
    @Autowired
    AuthRepository users;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired
    jakarta.validation.Validator validator;
    final JsonMapper json = JsonMapper.builder().build();
    static final String PASSWORD = "a-long-test-password-2026";

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM telemetry.auth_refresh_tokens");
        jdbc.update("DELETE FROM telemetry.auth_users");
        bootstrap.run(null);
    }

    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType("application/json").content(json.writeValueAsString(body));
    }

    JsonNode register(String name) throws Exception {
        return read(mvc.perform(body(post("/api/auth/register"), Map.of("username", name, "email", name + "@example.test", "password", PASSWORD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString());
    }

    JsonNode read(String value) {
        return json.readTree(value);
    }

    JsonNode login(String name, String password) throws Exception {
        return read(mvc.perform(body(post("/api/auth/login"), Map.of("usernameOrEmail", name, "password", password)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString());
    }

    String admin() throws Exception {
        return login(properties.initialAdminUsername(), properties.initialAdminPassword()).path("accessToken").asString();
    }

    String access(JsonNode tokens) {
        return tokens.path("accessToken").asString();
    }

    String refresh(JsonNode tokens) {
        return tokens.path("refreshToken").asString();
    }

    MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    JsonNode rotate(String token, int expected) throws Exception {
        var result = mvc.perform(body(post("/api/auth/refresh"), Map.of("refreshToken", token))).andExpect(status().is(expected)).andReturn();
        return read(result.getResponse().getContentAsString());
    }

    @Test
    void registrationHashesAndNormalizesAndDefaultsToUser() throws Exception {
        register("Alice");
        var user = jdbc.queryForMap("SELECT * FROM telemetry.auth_users WHERE username='alice'");
        assertThat(user.get("email")).isEqualTo("alice@example.test");
        assertThat(passwords.matches(PASSWORD, (String) user.get("password_hash"))).isTrue();
        assertThat(user.get("password_hash")).isNotEqualTo(PASSWORD);
    }

    @Test
    void duplicateUsernameAndEmailReturnConflict() throws Exception {
        register("alice");
        for (var values : List.of(Map.of("username", "ALICE", "email", "other@example.test", "password", PASSWORD),
                Map.of("username", "other", "email", "ALICE@example.test", "password", PASSWORD))) {
            mvc.perform(body(post("/api/auth/register"), values)).andExpect(status().isConflict());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"username\":\"ab\",\"email\":\"bad\",\"password\":\"short\"}",
            "{\"username\":\"alice\",\"email\":\"a@example.test\",\"password\":\"long-test-password\",\"role\":\"ADMIN\"}",
            "{\"username\":\"alice\",\"email\":\"a@example.test\",\"password\":\"long-test-password\",\"roles\":[\"ADMIN\"]}"})
    void invalidRegistrationAndRoleInjectionRejected(String request) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(request)).andExpect(status().isBadRequest());
    }

    @Test
    void multibytePasswordCannotExceedBcryptLimit() throws Exception {
        mvc.perform(body(post("/api/auth/register"), Map.of("username", "alice", "email", "a@example.test", "password", "é".repeat(40))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginByUsernameAndEmailIssuesSignedTokensAndOnlyStoresRefreshDigest() throws Exception {
        var user = register("alice");
        for (String name : List.of("ALICE", "alice@example.test")) {
            var tokens = login(name, PASSWORD);
            var jwt = decoder.decode(access(tokens));
            assertThat(jwt.getSubject()).isEqualTo(user.path("id").asString());
            assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
            assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
            assertThat(tokens.path("tokenType").asString()).isEqualTo("Bearer");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.auth_refresh_tokens WHERE token_hash=?", Integer.class, AuthService.hash(refresh(tokens)))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.auth_refresh_tokens WHERE token_hash=?", Integer.class, refresh(tokens))).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "alice"})
    void invalidCredentialsRejected(String name) throws Exception {
        register("alice");
        mvc.perform(body(post("/api/auth/login"), Map.of("usernameOrEmail", name, "password", "wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid credentials or token"));
    }

    @Test
    void disabledUserCannotLogin() throws Exception {
        register("alice");
        jdbc.update("UPDATE telemetry.auth_users SET enabled=FALSE WHERE username='alice'");
        mvc.perform(body(post("/api/auth/login"), Map.of("usernameOrEmail", "alice", "password", PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailuresLockAccountAndExpiryAllowsLogin() throws Exception {
        register("alice");
        for (int i = 0; i < properties.maxLoginAttempts(); i++)
            mvc.perform(body(post("/api/auth/login"), Map.of("usernameOrEmail", "alice", "password", "bad"))).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/auth/login"), Map.of("usernameOrEmail", "alice", "password", PASSWORD))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE telemetry.auth_users SET locked_until=? WHERE username='alice'", java.time.OffsetDateTime.now().minusSeconds(1));
        login("alice", PASSWORD);
    }

    @Test
    void refreshRotatesAndReplayInvalidatesAllSessions() throws Exception {
        register("alice");
        var first = login("alice", PASSWORD);
        var second = rotate(refresh(first), 200);
        assertThat(refresh(second)).isNotEqualTo(refresh(first));
        mvc.perform(bearer(get("/api/auth/me"), access(second))).andExpect(status().isOk());
        rotate(refresh(first), 401);
        rotate(refresh(second), 401);
        mvc.perform(bearer(get("/api/auth/me"), access(second))).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshAllowsAtMostOneSuccess() throws Exception {
        register("alice");
        var first = login("alice", PASSWORD);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> action = () -> {
                start.await();
                try {
                    service.refresh(refresh(first));
                    return 200;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    return e.getStatusCode().value();
                }
            };
            var a = pool.submit(action);
            var b = pool.submit(action);
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 401);
        }
    }

    @Test
    void expiredAndUnknownRefreshTokensRejected() throws Exception {
        register("alice");
        var tokens = login("alice", PASSWORD);
        jdbc.update("UPDATE telemetry.auth_refresh_tokens SET expires_at=?", java.time.OffsetDateTime.now().minusSeconds(1));
        rotate(refresh(tokens), 401);
        rotate("x".repeat(43), 401);
        mvc.perform(body(post("/api/auth/refresh"), Map.of("refreshToken", "malformed"))).andExpect(status().isBadRequest());
    }

    @Test
    void logoutRevokesRefreshAndAccessAndIsIdempotent() throws Exception {
        register("alice");
        var tokens = login("alice", PASSWORD);
        for (int i = 0; i < 2; i++)
            mvc.perform(body(post("/api/auth/logout"), Map.of("refreshToken", refresh(tokens)))).andExpect(status().isNoContent());
        rotate(refresh(tokens), 401);
        mvc.perform(bearer(get("/api/auth/me"), access(tokens))).andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedLogoutCannotRevokeANewSession() throws Exception {
        register("alice");
        var old = login("alice", PASSWORD);
        mvc.perform(body(post("/api/auth/logout"), Map.of("refreshToken", refresh(old)))).andExpect(status().isNoContent());
        var current = login("alice", PASSWORD);
        mvc.perform(body(post("/api/auth/logout"), Map.of("refreshToken", refresh(old)))).andExpect(status().isNoContent());
        mvc.perform(bearer(get("/api/auth/me"), access(current))).andExpect(status().isOk());
    }

    @Test
    void defaultDenyAndUserAdminAuthorization() throws Exception {
        var user = register("alice");
        var tokens = login("alice", PASSWORD);
        mvc.perform(get("/api/anomalies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/unlisted-route")).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/api/anomalies"), access(tokens))).andExpect(status().isOk());
        mvc.perform(bearer(get("/api/admin/users"), access(tokens))).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/documents/ingest"), access(tokens))).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/admin/users"), access(tokens))).andExpect(status().isForbidden());
        for (String id : List.of(user.path("id").asString(), UUID.randomUUID().toString()))
            mvc.perform(body(bearer(put("/api/admin/users/" + id + "/role"), access(tokens)), Map.of("role", "ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/admin/users"), admin())).andExpect(status().isOk()).andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void adminCreatesPromotesDisablesAndInvalidatesExistingTokens() throws Exception {
        String admin = admin();
        var response = mvc.perform(body(bearer(post("/api/admin/users"), admin), Map.of("username", "managed", "email", "managed@example.test", "password", PASSWORD, "role", "ADMIN")))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(response.getResponse().getContentAsString()).path("role").asString()).isEqualTo("ADMIN");
        var user = register("alice");
        var before = login("alice", PASSWORD);
        String id = user.path("id").asString();
        mvc.perform(body(bearer(put("/api/admin/users/" + id + "/role"), admin), Map.of("role", "ADMIN"))).andExpect(status().isOk());
        mvc.perform(bearer(get("/api/auth/me"), access(before))).andExpect(status().isUnauthorized());
        rotate(refresh(before), 401);
        var after = login("alice", PASSWORD);
        mvc.perform(bearer(get("/api/admin/users"), access(after))).andExpect(status().isOk());
        mvc.perform(body(bearer(put("/api/admin/users/" + id + "/status"), admin), Map.of("enabled", false))).andExpect(status().isOk());
        mvc.perform(bearer(get("/api/auth/me"), access(after))).andExpect(status().isUnauthorized());
        rotate(refresh(after), 401);
        mvc.perform(body(bearer(put("/api/admin/users/" + id + "/status"), admin), Map.of("enabled", true))).andExpect(status().isOk());
        login("alice", PASSWORD);
    }

    @Test
    void signedTokensWithInvalidClaimsAreRejected() throws Exception {
        var user = register("alice");
        for (String fault : List.of("expired", "issuer", "audience", "type", "missing-expiry")) {
            var builder = JwtClaimsSet.builder().subject(user.path("id").asString()).issuedAt(Instant.now().minusSeconds(120))
                    .issuer(fault.equals("issuer") ? "wrong" : properties.issuer())
                    .audience(List.of(fault.equals("audience") ? "wrong" : properties.audience()))
                    .claim("token_use", fault.equals("type") ? "refresh" : "access").claim("ver", 0);
            if (!fault.equals("missing-expiry"))
                builder.expiresAt(Instant.now().plusSeconds(fault.equals("expired") ? -60 : 300));
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build())).getTokenValue();
            mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isUnauthorized());
        }
        var token = access(login("alice", PASSWORD));
        mvc.perform(bearer(get("/api/auth/me"), token.substring(0, token.lastIndexOf('.') + 1) + "A".repeat(43))).andExpect(status().isUnauthorized());
    }

    @Test
    void initialAdminIsIdempotentAndUsesConfiguredHashedCredentials() {
        bootstrap.run(null);
        bootstrap.run(null);
        var admin = jdbc.queryForMap("SELECT * FROM telemetry.auth_users WHERE role='ADMIN'");
        assertThat(admin.get("username")).isEqualTo(properties.initialAdminUsername());
        assertThat(admin.get("email")).isEqualTo(properties.initialAdminEmail());
        assertThat(passwords.matches(properties.initialAdminPassword(), (String) admin.get("password_hash"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.auth_users", Integer.class)).isEqualTo(1);
    }

    AuthProperties bootstrapProperties(String username, String email, String password) {
        return new AuthProperties(properties.jwtSecret(), properties.issuer(), properties.audience(), properties.accessTtl(),
                properties.refreshTtl(), properties.bcryptStrength(), properties.maxLoginAttempts(), properties.lockout(),
                properties.corsOrigins(), username, email, password);
    }

    @Test
    void bootstrapNeedsConfigurationOnlyWhenAdminAbsent() {
        var missing = new InitialAdminInitializer(users, service, bootstrapProperties("", "", ""), transactions, validator);
        missing.run(null);
        jdbc.update("DELETE FROM telemetry.auth_users");
        assertThatThrownBy(() -> missing.run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.auth_users", Integer.class)).isZero();
    }

    @Test
    void bootstrapNeverPromotesAnExistingUser() {
        jdbc.update("DELETE FROM telemetry.auth_users");
        service.create(properties.initialAdminUsername(), properties.initialAdminEmail(), PASSWORD, AuthRepository.Role.USER);
        assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT role FROM telemetry.auth_users", String.class)).isEqualTo("USER");
    }

    @Test
    void concurrentBootstrapCreatesOneAdmin() throws Exception {
        jdbc.update("DELETE FROM telemetry.auth_users");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> bootstrap.run(null));
            var b = pool.submit(() -> bootstrap.run(null));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.auth_users", Integer.class)).isEqualTo(1);
    }

    @Test
    void adminInputValidationAndSelfProtection() throws Exception {
        var login = login(properties.initialAdminUsername(), properties.initialAdminPassword());
        String id = login.path("user").path("id").asString();
        mvc.perform(body(bearer(put("/api/admin/users/" + id + "/role"), access(login)), Map.of("role", "USER"))).andExpect(status().isConflict());
        mvc.perform(body(bearer(put("/api/admin/users/" + id + "/status"), access(login)), Map.of("enabled", false))).andExpect(status().isConflict());
        mvc.perform(body(bearer(put("/api/admin/users/" + id + "/role"), access(login)), Map.of("role", "ROOT"))).andExpect(status().isBadRequest());
        mvc.perform(bearer(get("/api/admin/users?limit=101"), access(login))).andExpect(status().isBadRequest());
    }

    @Test
    void queryStringAndDuplicateBearerCredentialsAreRejected() throws Exception {
        register("alice");
        var login = login("alice", PASSWORD);
        mvc.perform(get("/api/auth/me").param("access_token", access(login))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access(login), "Bearer " + access(login)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerDeclaresBearerAndPublicAuthOperations() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/actuator/health'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty());
    }

    @Test
    void corsAllowsOnlyConfiguredOriginAndNoCookieCredentials() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:4200").header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/auth/login").header("Origin", "https://evil.test").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
