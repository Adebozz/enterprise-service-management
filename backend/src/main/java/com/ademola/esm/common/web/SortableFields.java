package com.ademola.esm.common.web;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Explicit allow-list of properties a client may sort by.
 *
 * <p>Spring Data will happily sort by <em>any</em> entity property named in {@code ?sort=}. Without
 * an allow-list, {@code ?sort=passwordHash} would reveal the relative order of password hashes, and
 * unknown properties would surface as 500 errors.
 */
public record SortableFields(Set<String> allowed) {

    public static SortableFields of(String... properties) {
        return new SortableFields(Set.of(properties));
    }

    public Pageable validate(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new BusinessRuleException(
                        ErrorCode.INVALID_SORT_PROPERTY,
                        "Cannot sort by '%s'. Allowed: %s"
                                .formatted(
                                        order.getProperty(),
                                        String.join(
                                                ", ", allowed.stream().sorted().toList())));
            }
        }
        return pageable;
    }
}
