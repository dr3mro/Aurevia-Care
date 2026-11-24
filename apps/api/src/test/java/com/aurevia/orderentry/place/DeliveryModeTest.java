package com.aurevia.orderentry.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Delivery mode vocabulary: the recorded clinical choice and its channel flags. */
class DeliveryModeTest {

    @Test
    void parses_each_approved_mode_case_insensitively() {
        assertEquals(DeliveryMode.ELECTRONIC, DeliveryMode.parse("electronic"));
        assertEquals(DeliveryMode.PRINT, DeliveryMode.parse(" Print "));
        assertEquals(DeliveryMode.BOTH, DeliveryMode.parse("BOTH"));
    }

    @Test
    void refuses_a_missing_mode() {
        assertEquals(422, assertThrows(ApiException.class, () -> DeliveryMode.parse(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> DeliveryMode.parse("  ")).status());
        assertEquals("INVALID_DELIVERY_MODE",
                assertThrows(ApiException.class, () -> DeliveryMode.parse(null)).code());
    }

    @Test
    void refuses_an_unapproved_mode() {
        assertEquals(422, assertThrows(ApiException.class, () -> DeliveryMode.parse("fax")).status());
    }

    @Test
    void electronic_flag_includes_only_electronic_and_both() {
        assertTrue(DeliveryMode.ELECTRONIC.electronic());
        assertFalse(DeliveryMode.PRINT.electronic());
        assertTrue(DeliveryMode.BOTH.electronic());
    }

    @Test
    void print_flag_includes_only_print_and_both() {
        assertFalse(DeliveryMode.ELECTRONIC.print());
        assertTrue(DeliveryMode.PRINT.print());
        assertTrue(DeliveryMode.BOTH.print());
    }
}
