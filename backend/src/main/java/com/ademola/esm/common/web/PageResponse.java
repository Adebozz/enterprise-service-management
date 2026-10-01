package com.ademola.esm.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paginated results.
 *
 * <p>We don't serialise Spring Data's {@code Page} directly. Its JSON structure is an internal
 * detail that has changed between versions, and our API contract (and the TypeScript types
 * generated from it) must not change when we upgrade a library.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().<T>map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
