package com.ademola.esm.user;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    boolean existsByEmail(String normalisedEmail);

    Optional<User> findByEmail(String normalisedEmail);

    boolean existsByRoleAndActiveTrue(Role role);

    /**
     * Locks every active user with the given role ({@code SELECT ... FOR UPDATE}).
     *
     * <p>Used to serialise "is there another admin?" checks: a concurrent transaction running the
     * same query blocks until we commit, then re-evaluates its WHERE clause against the committed
     * rows, so two admins can't demote each other at the same time and leave the system with none.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.active = true")
    List<User> lockActiveUsersWithRole(Role role);
}
