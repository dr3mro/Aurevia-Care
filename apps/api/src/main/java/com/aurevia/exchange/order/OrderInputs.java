package com.aurevia.exchange.order;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.aurevia.api.ApiException;

/**
 * Pure validation at the order-exchange trust boundary.
 *
 * <p>Every method is total: it either returns a normalized value or raises a
 * machine-readable 422 before any database work begins. Identifiers are opaque;
 * no client-supplied value is trusted as an organization, actor or version.</p>
 */
public final class OrderInputs {

    /** Separator used by the canonical released-content projection. */
    public static final String SEPARATOR = "\u001f";

    private static final Set<String> PRIORITIES = Set.of("routine", "urgent", "stat");

    private OrderInputs() {
    }

    /**
     * Validate bounded text and return its trimmed form.
     *
     * @param value     caller-supplied text
     * @param field     field name used in the error message
     * @param maxLength inclusive maximum length before trimming
     * @return trimmed text
     * @throws ApiException when the value is missing, blank, over length or contains the separator
     */
    public static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf(SEPARATOR) >= 0) {
            throw new ApiException(422, "INVALID_INPUT", field + " has invalid text");
        }
        return value.trim();
    }

    /**
     * Parse an opaque identifier without assigning it any meaning.
     *
     * @param value caller-supplied identifier
     * @param field field name used in the error message
     * @return canonical lowercase identifier text
     * @throws ApiException when the value is not a UUID
     */
    public static String uuid(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_INPUT", field + " is required");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_INPUT", field + " must be a UUID");
        }
    }

    /**
     * Normalize an approved order priority.
     *
     * @param value caller-supplied priority
     * @return lowercase approved priority
     * @throws ApiException when the value is missing or not approved
     */
    public static String priority(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_PRIORITY", "priority is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!PRIORITIES.contains(normalized)) {
            throw new ApiException(422, "INVALID_PRIORITY", "priority is not approved");
        }
        return normalized;
    }

    /**
     * Validate an inclusive numeric bound.
     *
     * @param value caller-supplied number
     * @param field field name used in the error message
     * @param min   smallest accepted value
     * @param max   largest accepted value
     * @return the accepted number
     * @throws ApiException when the value is missing or outside the bounds
     */
    public static int bounded(Integer value, String field, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new ApiException(422, "INVALID_INPUT", field + " is outside its bounds");
        }
        return value;
    }
}
