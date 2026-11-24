package com.aurevia.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** ARC-04 failure translation: constraint SQLSTATEs map to stable API errors. */
class SqlFailureTranslatorTest {

    /** A throwable whose cause chain loops; a naive walk would spin forever. */
    private static final class SelfCausingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    private static DataIntegrityViolationException failure(String sqlState) {
        return new DataIntegrityViolationException("write failed",
                new SQLException("constraint", sqlState));
    }

    @Test
    void mapsExclusionViolationToSlotConflictWithoutLeakingTheProtectedResource() {
        ApiException translated = SqlFailureTranslator.translate(failure("23P01"));
        assertEquals(409, translated.status());
        assertEquals("SLOT_CONFLICT", translated.code());
        assertEquals("the protected resource is already booked for that slot", translated.getMessage());
    }

    @Test
    void mapsInvisibleReferenceToNotFound() {
        ApiException translated = SqlFailureTranslator.translate(failure("23503"));
        assertEquals(404, translated.status());
        assertEquals("NOT_FOUND", translated.code());
    }

    @Test
    void mapsAnythingElseToAGenericConflict() {
        assertEquals("CONFLICT", SqlFailureTranslator.translate(failure("23514")).code());
        assertEquals(409, SqlFailureTranslator.translate(failure("23514")).status());
        ApiException noSqlState = SqlFailureTranslator.translate(
                new DataIntegrityViolationException("plain"));
        assertEquals("CONFLICT", noSqlState.code());
        ApiException nested = SqlFailureTranslator.translate(new DataIntegrityViolationException(
                "outer", new IllegalStateException("inner",
                new SQLException("constraint", "23P01"))));
        assertEquals("SLOT_CONFLICT", nested.code());
        ApiException nullState = SqlFailureTranslator.translate(
                new DataIntegrityViolationException("x", new SQLException("y")));
        assertEquals("CONFLICT", nullState.code());
        ApiException selfCause = SqlFailureTranslator.translate(
                new DataIntegrityViolationException("loop", new SelfCausingException()));
        assertEquals("CONFLICT", selfCause.code());
    }
}
