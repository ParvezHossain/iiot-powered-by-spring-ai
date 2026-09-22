package com.iiot.auth;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {
    @Bean
    SecretKey jwtKey(AuthProperties p) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(p.jwtSecret());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("AUTH_JWT_SECRET must be base64 of at least 32 random bytes");
        }
        if (bytes.length < 32)
            throw new IllegalArgumentException("AUTH_JWT_SECRET must be base64 of at least 32 random bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    PasswordEncoder passwordEncoder(AuthProperties p) {
        return new BCryptPasswordEncoder(p.bcryptStrength());
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, AuthProperties p) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                && jwt.getAudience().contains(p.audience()) && "access".equals(jwt.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(java.time.Duration.ZERO), new JwtIssuerValidator(p.issuer()), claims));
        return decoder;
    }

    // MCP's existing highest-precedence filter validates its own service credential.
    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/mcp", "/mcp/**").csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthRepository users, AuthProperties p,
                                    @org.springframework.beans.factory.annotation.Value("${auth.requests-per-minute:30}") int rateLimit) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(p.corsOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return http.addFilterBefore(new AuthRateLimitFilter(rateLimit, java.time.Clock.systemUTC()),
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .csrf(c -> c.disable()).cors(c -> c.configurationSource(source))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/admin/**", "/api/documents/ingest").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((q, r, x) -> error(r, 401, "Authentication required"))
                        .accessDeniedHandler((q, r, x) -> error(r, 403, "Access denied")))
                .oauth2ResourceServer(o -> o.bearerTokenResolver(request -> {
                            if (java.util.Collections.list(request.getHeaders("Authorization")).size() > 1)
                                throw new InvalidBearerTokenException("Invalid authorization header");
                            return new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver().resolve(request);
                        }).authenticationEntryPoint((q, r, x) -> error(r, 401, "Authentication required"))
                        .accessDeniedHandler((q, r, x) -> error(r, 403, "Access denied"))
                        .jwt(j -> j.jwtAuthenticationConverter(jwt -> {
                            try {
                                if (jwt.getSubject() == null) throw new IllegalArgumentException();
                                var user = users.find(UUID.fromString(jwt.getSubject()), false);
                                Object version = jwt.getClaims().get("ver");
                                if (user == null || !user.enabled() || !(version instanceof Number number) || number.longValue() != user.version())
                                    throw new IllegalArgumentException();
                                return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())), user.id().toString());
                            } catch (IllegalArgumentException ex) {
                                throw new InvalidBearerTokenException("Invalid access token");
                            }
                        }))).build();
    }

    static void error(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
