package com.ademola.esm.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * HTTP security rules.
 *
 * <p>M0 (bootstrap): health and API docs are public; everything else requires authentication, and
 * since no login mechanism exists yet, all other requests are rejected with 401. JWT authentication
 * replaces this in M2.
 *
 * <p>Authentication/authorization failures are delegated to the MVC {@link HandlerExceptionResolver}
 * so they are rendered by {@code GlobalExceptionHandler} in the same ProblemDetail format as every
 * other error, instead of Spring Security's default HTML/blank responses.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        return http
                // Stateless API: no server-side session, no cookies for authentication of API calls,
                // therefore CSRF tokens are not applicable. (The refresh-token cookie added in M2 is
                // scoped to /api/auth and protected by SameSite=Strict plus an Origin check.)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_ENDPOINTS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) ->
                                exceptionResolver.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) ->
                                exceptionResolver.resolveException(request, response, null, e)))
                .build();
    }
}
