package com.ademola.esm.user;

import com.ademola.esm.common.persistence.LikePatterns;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Dynamic, parameterised filters for the admin user search. Optional criteria are simply omitted. */
final class UserSpecifications {

    private UserSpecifications() {}

    static Specification<User> matching(UserSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (criteria.q() != null && !criteria.q().isBlank()) {
                String pattern = LikePatterns.containsIgnoringCase(criteria.q());
                predicates.add(cb.or(
                        cb.like(root.get("email"), pattern, LikePatterns.ESCAPE),
                        cb.like(cb.lower(root.get("displayName")), pattern, LikePatterns.ESCAPE)));
            }
            if (criteria.role() != null) {
                predicates.add(cb.equal(root.get("role"), criteria.role()));
            }
            if (criteria.active() != null) {
                predicates.add(cb.equal(root.get("active"), criteria.active()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
