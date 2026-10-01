package com.ademola.esm.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull Role role,
        // Length is the main strength control (NIST SP 800-63B). Max 72 because BCrypt only uses
        // the first 72 bytes; the service also checks the UTF-8 byte length.
        @NotBlank @Size(min = 12, max = 72) String initialPassword) {}
