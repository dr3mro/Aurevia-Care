package com.aurevia.pharmacyflow.substitution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Boundary validation for substitution commands. */
class SubstitutionInputsTest {

    @Test
    void requireTextRejectsNullAndBlankAndAcceptsValue() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> SubstitutionInputs.requireText(null, "CODE", "message")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> SubstitutionInputs.requireText("  ", "CODE", "message")).status());
        assertDoesNotThrow(() -> SubstitutionInputs.requireText("value", "CODE", "message"));
    }

    @Test
    void requirePositiveRejectsZeroAndAcceptsOne() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> SubstitutionInputs.requirePositive(0, "CODE", "message")).status());
        assertDoesNotThrow(() -> SubstitutionInputs.requirePositive(1, "CODE", "message"));
    }

    @Test
    void optionalNoteRejectsOnlyOverlongValues() {
        assertDoesNotThrow(() -> SubstitutionInputs.optionalNote(null, "CODE", "message"));
        assertEquals("short", SubstitutionInputs.optionalNote("short", "CODE", "message"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> SubstitutionInputs.optionalNote("x".repeat(501), "CODE", "message")).status());
    }

    @Test
    void newIdReturnsDistinctOpaqueIdentifiers() {
        String first = SubstitutionInputs.newId();
        String second = SubstitutionInputs.newId();
        assertNotEquals(first, second);
        assertEquals(36, first.length());
    }
}
