package com.aurevia.exchange.trend;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

import com.aurevia.api.ApiException;

/**
 * Result qualifier carried with a numeric observation (XCH-07).
 *
 * <p>A qualified numeric result such as {@code < 3.0} is <em>not</em> a point
 * value: plotting it at its literal number would assert false precision. The
 * qualifier is preserved verbatim so the result stays visible outside the
 * comparable trend.</p>
 */
public enum Qualifier {

    /** An unqualified numeric result, eligible for trending. */
    NONE("none"),
    /** A less-than result, for example {@code < 3.0}. */
    LESS_THAN("less_than"),
    /** A greater-than result, for example {@code > 20}. */
    GREATER_THAN("greater_than");

    private final String wire;

    /**
     * Bind the stable wire representation of a result qualifier.
     *
     * @param wire lower-case value accepted on input and stored in the database
     */
    Qualifier(String wire) {
        this.wire = wire;
    }

    /**
     * Parse a wire qualifier without ever guessing an unknown value.
     *
     * @param wire {@code null}, blank, {@code none}, {@code <}, {@code >},
     *             {@code less_than} or {@code greater_than} (case-insensitive)
     * @return the parsed qualifier; missing values mean {@code NONE}
     * @throws ApiException when the value is not a known qualifier
     */
    public static Qualifier from(String wire) {
        if (wire == null || wire.isBlank()) {
            return NONE;
        }
        return switch (wire.toLowerCase(Locale.ROOT)) {
            case "none" -> NONE;
            case "<", "less_than" -> LESS_THAN;
            case ">", "greater_than" -> GREATER_THAN;
            default -> throw new ApiException(422, "INVALID_QUALIFIER", "unknown result qualifier");
        };
    }

    /**
     * Return the stable wire representation.
     *
     * <p>Annotated so responses expose the same lower-case value the database
     * stores and {@code from(String)} accepts; the enum name is never leaked
     * to API clients.</p>
     *
     * @return {@code none}, {@code less_than} or {@code greater_than}
     */
    @JsonValue
    public String wire() {
        return wire;
    }
}
