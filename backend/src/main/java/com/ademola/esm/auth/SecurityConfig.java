package com.ademola.esm.auth;

import com.ademola.esm.user.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * HTTP security rules.
 *
 * <p>Requests carry {@code Authorization: Bearer <jwt>}. Spring Security's
 * BearerTokenAuthenticationFilter extracts it, our {@code JwtDecoder} verifies the signature,
 * expiry and issuer, and {@link CurrentUserJwtConverter} turns the claims into a
 * {@link CurrentUser}. URL rules are then checked here, and method rules via
 * {@code @PreAuthorize}.
 *
 * <p>Authentication and authorization failures are delegated to the MVC
 * {@link HandlerExceptionResolver} so {@code GlobalExceptionHandler} renders them in the same
 * ProblemDetail format as every other error.
 */
@Configuration
@EnableMethodSecurity // activates @PreAuthorize on service methods
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    // Authenticated by credentials (login) or by the refresh cookie, not by an access token.
    private static final String[] AUTH_ENDPOINTS = {"/api/auth/login", "/api/auth/refresh", "/api/auth/logout"};

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, e) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); // RFC 6750
            exceptionResolver.resolveException(request, response, null, e);
        };
        AccessDeniedHandler accessDenied =
                (request, response, e) -> exceptionResolver.resolveException(request, response, null, e);

        return http
                // Stateless API authenticated by bearer tokens, which browsers never attach
                // automatically, so CSRF tokens don't apply. The one cookie (refresh token) is
                // limited to /api/auth, SameSite=Strict, and checked by OriginPolicy.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_ENDPOINTS)
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, AUTH_ENDPOINTS)
                        .permitAll()
                        // Coarse URL rule as a first layer; services repeat the check with @PreAuthorize.
                        .requestMatchers("/api/admin/**")
                        .hasRole(Role.ADMIN.name())
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(new CurrentUserJwtConverter()))
                                .authenticationEntryPoint(entryPoint)
                                .accessDeniedHandler(accessDenied))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .build();
    }

    /**
     * ADMIN > TEAM_LEAD > AGENT > REQUESTER, generated from the {@link Role} enum. Spring Security
     * applies this bean to both URL rules and {@code @PreAuthorize}, so {@code hasRole('AGENT')} also
     * admits team leads and admins without listing every role at every check.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(Role.springSecurityHierarchy());
    }
}
