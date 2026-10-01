package com.ademola.esm.auth;

/** Claim names used in our access tokens. Kept minimal: identity and role only, no personal data. */
final class JwtClaims {

    static final String ROLE = "role";
    static final String NAME = "name";

    private JwtClaims() {}
}
