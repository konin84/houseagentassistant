package com.digitalpartner.houseagent.common.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One error shape for every service, so clients parse failures the same way no
 * matter which service produced them.
 *
 * @param code    stable machine-readable identifier, e.g. HOUSE_NOT_FOUND
 * @param message human-readable summary, safe to show a user
 * @param details field-level problems, empty for non-validation errors
 */
public record ApiError(
        String code,
        String message,
        List<FieldError> details,
        Instant timestamp) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of(), Instant.now());
    }

    public static ApiError validation(Map<String, String> fieldMessages) {
        List<FieldError> errors = fieldMessages.entrySet().stream()
                .map(e -> new FieldError(e.getKey(), e.getValue()))
                .toList();
        return new ApiError("VALIDATION_FAILED", "Request validation failed", errors, Instant.now());
    }
}
