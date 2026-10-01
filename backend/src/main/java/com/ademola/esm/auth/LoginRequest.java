package com.ademola.esm.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        // Upper bound stops absurdly large inputs reaching the (deliberately slow) password hasher.
        @NotBlank @Size(max = 256) String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]"; // never log the password
    }
}
