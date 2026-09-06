package com.rlna.dto;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Uniform pagination envelope for every list endpoint (Section 21.3). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    public static <T> PageResponse<T> of(List<T> items) {
        return new PageResponse<>(items, 0, items.size(), items.size(), 1);
    }
}
