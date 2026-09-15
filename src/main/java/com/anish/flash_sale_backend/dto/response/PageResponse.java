package com.example.flashsale.dto.response;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope. We never expose Spring's Page directly:
 * that would leak PageImpl serialization details into the API contract.
 *
 * Pagination matters because GET /api/products could match thousands of
 * rows - loading them all into memory would waste heap and bandwidth;
 * LIMIT/OFFSET keeps every request O(page size).
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}
