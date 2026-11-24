package com.aurevia.it.exchangeorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.order.OrderInputs;

/** S-G2-01 validation branches for XCH-01. Synthetic values only; no database. */
class OrderInputsTest {

    @Test
    void textAcceptsTrimmedValueAtTheBoundary() {
        assertEquals("CBC", OrderInputs.text("  CBC  ", "orderCode", 12));
        assertEquals("a".repeat(2000), OrderInputs.text("a".repeat(2000), "question", 2000));
    }

    @Test
    void textRejectsNullBlankAndOversizedValues() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.text(null, "orderCode", 12)).status());
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> OrderInputs.text("   ", "orderCode", 12)).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.text("a".repeat(2001), "question", 2000)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.text("a" + OrderInputs.SEPARATOR + "b", "question", 2000)).status());
        assertTrue(assertThrows(ApiException.class,
                () -> OrderInputs.text(null, "orderCode", 12)).getMessage().contains("orderCode"));
    }

    @Test
    void uuidAcceptsCanonicalInputAndRejectsMalformedInput() {
        assertEquals("11111111-1111-1111-1111-111111111111",
                OrderInputs.uuid("11111111-1111-1111-1111-111111111111", "patientId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.uuid("not-a-uuid", "patientId")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.uuid(null, "patientId")).status());
        assertTrue(assertThrows(ApiException.class,
                () -> OrderInputs.uuid("bad", "patientId")).getMessage().contains("patientId"));
    }

    @Test
    void priorityAcceptsEveryApprovedValueAndRejectsTheRest() {
        assertEquals("routine", OrderInputs.priority("ROUTINE"));
        assertEquals("urgent", OrderInputs.priority("urgent"));
        assertEquals("stat", OrderInputs.priority(" STAT "));
        ApiException failure = assertThrows(ApiException.class, () -> OrderInputs.priority("soon"));
        assertEquals(422, failure.status());
        assertEquals("INVALID_PRIORITY", failure.code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.priority(null)).status());
    }

    @Test
    void boundedAcceptsInclusiveBoundsAndRejectsNullAndOutOfRangeValues() {
        assertEquals(1, OrderInputs.bounded(1, "durationMinutes", 1, 43200));
        assertEquals(43200, OrderInputs.bounded(43200, "durationMinutes", 1, 43200));
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.bounded(null, "durationMinutes", 1, 43200)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.bounded(0, "durationMinutes", 1, 43200)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OrderInputs.bounded(43201, "durationMinutes", 1, 43200)).status());
        assertTrue(assertThrows(ApiException.class,
                () -> OrderInputs.bounded(0, "durationMinutes", 1, 43200)).getMessage()
                .contains("durationMinutes"));
    }
}
