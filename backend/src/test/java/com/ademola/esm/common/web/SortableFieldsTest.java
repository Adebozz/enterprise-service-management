package com.ademola.esm.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SortableFieldsTest {

    private final SortableFields sortable = SortableFields.of("email", "displayName");

    @Test
    void acceptsAllowedPropertiesAndUnsortedRequests() {
        Pageable sorted = PageRequest.of(0, 20, Sort.by("email").and(Sort.by("displayName")));
        assertThat(sortable.validate(sorted)).isSameAs(sorted);
        assertThat(sortable.validate(PageRequest.of(0, 20))).isNotNull();
    }

    @Test
    void rejectsPropertiesOutsideTheAllowList() {
        assertThatThrownBy(() -> sortable.validate(PageRequest.of(0, 20, Sort.by("passwordHash"))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code())
                .isEqualTo(ErrorCode.INVALID_SORT_PROPERTY);
    }
}
