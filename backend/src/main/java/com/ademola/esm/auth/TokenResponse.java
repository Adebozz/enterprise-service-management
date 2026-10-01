package com.ademola.esm.auth;

/**
 * Body of login/refresh responses. The refresh token is NOT here: it travels only in the HttpOnly
 * cookie, so frontend JavaScript never sees it.
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn, AuthenticatedUser user) {}
