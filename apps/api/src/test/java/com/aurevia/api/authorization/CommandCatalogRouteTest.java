package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** OPS-02/ARC-04 route lookup: a matched handler must resolve to exactly one catalog command. */
class CommandCatalogRouteTest {

    @Test
    void findsCatalogCommandByNormalizedRouteDespiteDifferentVariableNames() {
        CommandCatalog catalog = CommandCatalog.synthetic();

        assertEquals("acceptOrder", catalog.findByPath("POST", "/api/v1/orders/{orderId}/accept")
                .orElseThrow().id());
        assertEquals("createFacility", catalog.findByPath("POST", "/api/v1/facilities")
                .orElseThrow().id());
        assertEquals("approveConfiguration",
                catalog.findByPath("POST", "/api/v1/configuration/{configKey}/versions/{version}/approve")
                        .orElseThrow().id());
    }

    @Test
    void unknownMethodOrRouteAndBlankInputsFailClosed() {
        CommandCatalog catalog = CommandCatalog.synthetic();

        assertTrue(catalog.findByPath("GET", "/api/v1/facilities").isEmpty());
        assertTrue(catalog.findByPath("POST", "/api/v1/orders/{orderId}/decline").isEmpty());
        assertTrue(catalog.findByPath(null, "/api/v1/facilities").isEmpty());
        assertTrue(catalog.findByPath("POST", null).isEmpty());
        assertTrue(catalog.findByPath("  ", "/api/v1/facilities").isEmpty());
        assertTrue(catalog.findByPath("POST", "   ").isEmpty());
    }

    @Test
    void normalizingBothSidesRejectsTwoDefinitionsOnTheSameRoute() {
        CommandDefinition first = new CommandDefinition("firstCommand", "ACTION", "POST",
                "/api/v1/orders/{id}/accept", "ActionPolicy");
        CommandDefinition second = new CommandDefinition("secondCommand", "ACTION", "POST",
                "/api/v1/orders/{orderId}/accept", "ActionPolicy");

        assertEquals("duplicate command route: POST /api/v1/orders/{}/accept",
                assertThrows(IllegalArgumentException.class,
                        () -> new CommandCatalog(List.of(first, second))).getMessage());
    }

    @Test
    void literalDuplicatePathIsRejectedBeforeTheNormalizedRouteCheck() {
        CommandDefinition first = new CommandDefinition("firstCommand", "ACTION", "POST",
                "/api/v1/same", "ActionPolicy");
        CommandDefinition second = new CommandDefinition("secondCommand", "ACTION", "POST",
                "/api/v1/same", "ActionPolicy");

        assertEquals("duplicate command path: /api/v1/same",
                assertThrows(IllegalArgumentException.class,
                        () -> new CommandCatalog(List.of(first, second))).getMessage());
        assertEquals("commands", assertThrows(NullPointerException.class,
                () -> new CommandCatalog(null)).getMessage());
    }

    @Test
    void routesAreImmutableAndCoverEveryCommand() {
        CommandCatalog catalog = CommandCatalog.synthetic();

        assertEquals(catalog.commands().size(), catalog.paths().size());
        assertTrue(catalog.paths().contains("/api/v1/facilities"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.paths().add("/api/v1/extra"));
    }
}
