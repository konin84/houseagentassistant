package com.digitalpartner.houseagent.property.api.dto;

import java.util.List;

/**
 * Envelope for paged results.
 *
 * <p>Every collection endpoint returns this rather than a bare array. Marketplace
 * search will be the highest-traffic endpoint on the platform and an unbounded list
 * endpoint is the classic way to discover that at 3am.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }
}
