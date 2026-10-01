package com.ademola.esm.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class PasswordConfig {

    /**
     * Delegating encoder: hashes with BCrypt today and stores the algorithm as a prefix
     * ({@code {bcrypt}$2a$10$...}). Existing hashes stay verifiable if we later switch the default
     * to Argon2, and can be upgraded transparently at next login.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
