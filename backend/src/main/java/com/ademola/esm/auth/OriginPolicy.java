package com.ademola.esm.auth;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * CSRF defence for the two endpoints authenticated by cookie (refresh, logout).
 *
 * <p>{@code SameSite=Strict} already stops browsers sending the cookie on cross-site requests; this
 * is a second, independent layer. Browsers always send {@code Origin} on cross-origin POSTs, so a
 * present-but-unlisted Origin is rejected. A missing Origin is allowed: it means a non-browser
 * client (curl, tests), which can't be tricked into CSRF because it has no ambient cookies.
 */
@Component
class OriginPolicy {

    private final Set<String> allowedOrigins;

    OriginPolicy(SecurityProperties properties) {
        this.allowedOrigins = Set.copyOf(properties.allowedOrigins());
    }

    void check(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new BusinessRuleException(ErrorCode.INVALID_ORIGIN, "Request origin is not allowed");
        }
    }
}
