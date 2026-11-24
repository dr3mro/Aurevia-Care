package com.aurevia.api.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The single canonical lowercase subject rule shared by every request-boundary
 * validator (IAM-01, IAM-02, ARC-03, ARC-04, OPS-02).
 *
 * <p>The rule is deliberately wider than the legacy strict key: dots and
 * underscores are valid inside the key, a colon and any non-ASCII script are
 * not. Every input class here is synthetic; no real identifier is used.</p>
 */
class SubjectTest {

    private static final String VALID = "patient-a";

    @Test
    void acceptsTheBoundedLowercaseAlphabet() {
        assertEquals(VALID, Subject.subject(VALID).value());
        assertEquals("ab", Subject.subject("ab").value(), "minimum length");
        assertEquals("a".repeat(64), Subject.subject("a".repeat(64)).value(), "maximum length");
        assertEquals("patient.one", Subject.subject("patient.one").value(), "dot is part of the canonical key");
        assertEquals("patient_one", Subject.subject("patient_one").value(), "underscore is part of the canonical key");
        assertEquals("a.b_c-d", Subject.subject("a.b_c-d").value(), "all separators combined");
    }

    @Test
    void rejectsNullAndEmptyText() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Subject.subject(null));
        assertEquals(Subject.INVALID_MESSAGE, missing.getMessage());
        assertEquals(Subject.INVALID_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> Subject.subject("")).getMessage());
    }

    @Test
    void rejectsOutOfBoundsLengths() {
        for (String value : List.of("a", "a".repeat(65))) {
            assertEquals(Subject.INVALID_MESSAGE,
                    assertThrows(IllegalArgumentException.class, () -> Subject.subject(value)).getMessage(),
                    "length=" + value.length());
        }
    }

    @Test
    void rejectsCharactersOutsideTheCanonicalAlphabet() {
        for (String value : List.of("Patient", "patient:one", ".patient", "_patient", "-patient", "مريض")) {
            assertEquals(Subject.INVALID_MESSAGE,
                    assertThrows(IllegalArgumentException.class, () -> Subject.subject(value)).getMessage(),
                    "subject=" + value);
        }
    }

    @Test
    void exposesTheSharedPatternFragmentForCompositeBoundaryKeys() {
        // The staff cursor embeds a subject followed by a UUID; it must be built
        // from the same fragment the primitive validates, never a second literal.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(Subject.PATTERN);
        assertTrue(pattern.matcher("patient.one").matches());
        assertTrue(pattern.matcher("patient_one").matches());
        assertFalse(pattern.matcher("patient:one").matches());
        assertTrue(pattern.matcher("a".repeat(64)).matches());
    }
}
