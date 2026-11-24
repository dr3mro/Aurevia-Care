package com.aurevia.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Boundary validation for the mail commands (MSG-02, ARC-07). */
class MailInputsTest {

    @Test
    void requireTextRejectsNullAndBlankAndReturnsValidText() {
        ApiException missing = assertThrows(ApiException.class,
                () -> MailInputs.requireText(null, "INVALID_ORGANIZATION", "required"));
        assertEquals(422, missing.status());
        assertEquals("INVALID_ORGANIZATION", missing.code());
        assertEquals("INVALID_ORGANIZATION", assertThrows(ApiException.class,
                () -> MailInputs.requireText("  ", "INVALID_ORGANIZATION", "required")).code());
        assertEquals("nurse-a", MailInputs.requireText("nurse-a", "INVALID_ORGANIZATION", "required"));
    }

    @Test
    void requirePositiveRejectsZeroAndNegativeButAcceptsOne() {
        ApiException invalid = assertThrows(ApiException.class,
                () -> MailInputs.requirePositive(0, "INVALID_VERSION", "positive"));
        assertEquals(422, invalid.status());
        assertThrows(ApiException.class,
                () -> MailInputs.requirePositive(-1, "INVALID_VERSION", "positive"));
        assertEquals(1, MailInputs.requirePositive(1, "INVALID_VERSION", "positive"));
    }

    @Test
    void orEmptyReturnsAnImmutableCopyOrAnEmptyList() {
        List<String> source = new ArrayList<>(List.of("a"));
        List<String> copied = MailInputs.orEmpty(source);
        source.add("b");
        assertEquals(List.of("a"), copied);
        assertThrows(UnsupportedOperationException.class, () -> copied.add("c"));
        assertTrue(MailInputs.orEmpty(null).isEmpty());
        assertFalse(MailInputs.orEmpty(List.of("x")).isEmpty());
    }

    @Test
    void newIdProducesDistinctNonBlankIdentifiers() {
        String first = MailInputs.newId();
        String second = MailInputs.newId();
        assertFalse(first.isBlank());
        assertNotEquals(first, second);
    }
}
