package com.ademola.esm.support;

import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates real users with real BCrypt hashes, so security tests can log in through the actual
 * login endpoint instead of faking an authenticated principal.
 */
@Component
public class TestUsers {

    public static final String PASSWORD = "integration-test-password";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String passwordHash;

    TestUsers(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordHash = passwordEncoder.encode(PASSWORD); // hash once; BCrypt is deliberately slow
    }

    public User create(String email, Role role) {
        return users.save(new User(email, displayNameFrom(email), passwordHash, role));
    }

    public PasswordEncoder passwordEncoder() {
        return passwordEncoder;
    }

    private static String displayNameFrom(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return Character.toUpperCase(local.charAt(0)) + local.substring(1);
    }
}
