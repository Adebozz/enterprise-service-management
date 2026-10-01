package com.ademola.esm.user;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * User roles, strictly hierarchical: each role has every permission of the roles below it.
 *
 * <p>This enum is the single source of truth. The database CHECK constraint lists the same values,
 * and Spring Security's role hierarchy is generated from {@link #springSecurityHierarchy()}.
 */
public enum Role {
    REQUESTER(1),
    AGENT(2),
    TEAM_LEAD(3),
    ADMIN(4);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    /** True if this role has (at least) the permissions of {@code other}. */
    public boolean includes(Role other) {
        return level >= other.level;
    }

    /** Support staff: may be a team member, see team queues and read internal notes. */
    public boolean isStaff() {
        return includes(AGENT);
    }

    public String authority() {
        return "ROLE_" + name();
    }

    /** E.g. {@code "ROLE_ADMIN > ROLE_TEAM_LEAD\nROLE_TEAM_LEAD > ROLE_AGENT\n..."}. */
    public static String springSecurityHierarchy() {
        Role[] descending = Arrays.stream(values())
                .sorted(Comparator.comparingInt((Role r) -> r.level).reversed())
                .toArray(Role[]::new);
        return java.util.stream.IntStream.range(0, descending.length - 1)
                .mapToObj(i -> descending[i].authority() + " > " + descending[i + 1].authority())
                .collect(Collectors.joining("\n"));
    }
}
