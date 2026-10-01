package com.ademola.esm.user;

import com.ademola.esm.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Locale;

/**
 * A person who can sign in. Users are deactivated rather than deleted, because tickets, comments and
 * the audit trail keep referring to them.
 *
 * <p>No public setters: state changes go through intention-revealing methods so invariants (such
 * as normalised email) can't be bypassed.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    /** For JPA only. */
    protected User() {}

    public User(String email, String displayName, String passwordHash, Role role) {
        this.email = normaliseEmail(email);
        this.displayName = displayName.trim();
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public void rename(String newDisplayName) {
        this.displayName = newDisplayName.trim();
    }

    void changeRole(Role newRole) {
        this.role = newRole;
    }

    void activate() {
        this.active = true;
    }

    void deactivate() {
        this.active = false;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }
}
