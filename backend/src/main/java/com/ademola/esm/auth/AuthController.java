package com.ademola.esm.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
class AuthController {

    private final AuthService authService;
    private final RefreshCookies cookies;
    private final OriginPolicy originPolicy;

    AuthController(AuthService authService, RefreshCookies cookies, OriginPolicy originPolicy) {
        this.authService = authService;
        this.cookies = cookies;
        this.originPolicy = originPolicy;
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Sign in. Returns an access token; sets the refresh-token cookie")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withSession(authService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    @Operation(
            operationId = "refreshSession",
            summary = "Exchange the refresh-token cookie for a new access token (rotates the cookie)")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        originPolicy.check(request);
        return withSession(authService.refresh(cookieValue(request)));
    }

    @PostMapping("/logout")
    @Operation(
            operationId = "logout",
            summary = "End this session: revokes the refresh-token family and clears the cookie")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        originPolicy.check(request);
        authService.logout(cookieValue(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withSession(AuthService.Session session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // tokens must never be cached by proxies/browsers
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookies.create(session.refreshToken()).toString())
                .body(session.body());
    }

    private String cookieValue(HttpServletRequest request) {
        var cookie = WebUtils.getCookie(request, cookies.name());
        return cookie == null ? null : cookie.getValue();
    }
}
