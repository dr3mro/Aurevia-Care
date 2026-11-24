package com.aurevia.api;

import java.sql.SQLException;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Translates a database integrity failure into the machine-readable API contract
 * (ARC-03, ARC-04) without leaking which protected resource collided.
 *
 * <p>{@code 23P01} is an exclusion-constraint violation: the protected slot is
 * already taken. {@code 23503} is a foreign-key violation, which under forced RLS
 * means the referenced row is not visible in the active tenant, so it is
 * concealed as not found. Anything else is a generic conflict: the command was
 * well formed but the durable state refused it.</p>
 */
public final class SqlFailureTranslator {

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private SqlFailureTranslator() {
    }

    /**
     * Map a data-integrity failure onto an API error.
     *
     * @param exception failure raised by the JDBC layer
     * @return the API error to raise
     */
    public static ApiException translate(DataIntegrityViolationException exception) {
        String sqlState = mostSpecificSqlState(exception);
        if (EXCLUSION_VIOLATION.equals(sqlState)) {
            return new ApiException(409, "SLOT_CONFLICT",
                    "the protected resource is already booked for that slot");
        }
        if (FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            return new ApiException(404, "NOT_FOUND", "referenced record not found");
        }
        return new ApiException(409, "CONFLICT", "the request conflicts with the stored record");
    }

    private static String mostSpecificSqlState(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return null;
    }
}
