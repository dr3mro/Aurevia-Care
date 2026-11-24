package com.aurevia.orderentry.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Catalog capture at the order trust boundary. */
class OrderSelectionTest {

    @Test
    void captures_a_catalog_item_and_version() {
        OrderSelection selection = new OrderSelection("item-cbc", 3);
        assertEquals("item-cbc", selection.itemId());
        assertEquals(3, selection.version());
    }

    @Test
    void refuses_a_missing_item_identifier() {
        assertEquals(422, assertThrows(ApiException.class, () -> new OrderSelection(null, 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new OrderSelection("  ", 1)).status());
    }

    @Test
    void refuses_a_non_positive_version() {
        ApiException error = assertThrows(ApiException.class, () -> new OrderSelection("item-cbc", 0));
        assertEquals(422, error.status());
        assertEquals("INVALID_ORDER_ITEM", error.code());
    }
}
