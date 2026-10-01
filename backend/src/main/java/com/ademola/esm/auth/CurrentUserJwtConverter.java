package com.ademola.esm.auth;

import com.ademola.esm.user.Role;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** Turns a verified JWT into our {@link CurrentUserAuthentication}. */
class CurrentUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            CurrentUser user = new CurrentUser(
                    UUID.fromString(jwt.getSubject()),
                    Role.valueOf(jwt.getClaimAsString(JwtClaims.ROLE)),
                    jwt.getClaimAsString(JwtClaims.NAME));
            return new CurrentUserAuthentication(user, jwt);
        } catch (IllegalArgumentException | NullPointerException e) {
            // Correctly signed but not a token we would have issued (e.g. unknown role): reject as 401.
            throw new InvalidBearerTokenException("Access token is missing required claims");
        }
    }
}
