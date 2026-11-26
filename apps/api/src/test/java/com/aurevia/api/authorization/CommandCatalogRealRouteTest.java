package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.authz.specialty.SpecialtyActions;
import com.aurevia.exchange.critical.CriticalActions;
import com.aurevia.exchange.release.ReleaseActionPolicy;
import com.aurevia.exchange.response.ExchangeResponseActionPolicy;
import com.aurevia.finance.FinanceActions;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.scheduling.checkin.WorklistPolicy;

/**
 * Handler-to-command mapping for delivered POST routes (ARC-03, ARC-04).
 *
 * <p>One matched Spring route template resolves to exactly one command. Concrete
 * request identifiers never select a command: only the normalized template is a
 * mapping key. The synthetic baseline stays registered so existing callers and
 * replay-scope keys remain stable; the real-route entries are additive.</p>
 */
class CommandCatalogRealRouteTest {

    private static final CommandCatalog CATALOG = CommandCatalog.synthetic();

    @Test
    void deliveredPostHandlersResolveToTheirOwnCommands() {
        assertMapping("/api/v1/clinic/verifications", "verifyClinicIdentity",
                WorklistPolicy.IDENTITY_VERIFY, CommandCatalog.WORKLIST_POLICY);
        assertMapping("/api/v1/clinic/appointments/{id}/check-in", "checkInClinicAppointment",
                WorklistPolicy.CHECK_IN, CommandCatalog.WORKLIST_POLICY);
        assertMapping("/api/v1/clinic/worklist/{id}/assign", "assignClinicWorkItem",
                WorklistPolicy.WORKLIST_ASSIGN, CommandCatalog.WORKLIST_POLICY);
        assertMapping("/api/v1/therapy/episodes", "acceptTherapyEpisode",
                SpecialtyActions.PT_ACCEPT_REFERRAL, CommandCatalog.SPECIALTY_POLICY);
        assertMapping("/api/v1/xch03/orders/{id}/accept", "acceptExchangeResponse",
                ExchangeResponseActionPolicy.EXCHANGE_ACCEPT, CommandCatalog.EXCHANGE_RESPONSE_POLICY);
        assertMapping("/api/v1/xch03/orders/{id}/reject", "rejectExchangeResponse",
                ExchangeResponseActionPolicy.EXCHANGE_REJECT, CommandCatalog.EXCHANGE_RESPONSE_POLICY);
        assertMapping("/api/v1/releases", "authorizePatientRelease",
                ReleaseActionPolicy.AUTHORIZE, CommandCatalog.RELEASE_POLICY);
        assertMapping("/api/v1/critical-results/{id}/acknowledge", "acknowledgeReportedCriticalResult",
                CriticalActions.ACKNOWLEDGE, CommandCatalog.CRITICAL_POLICY);
        assertMapping("/api/v1/finance/invoices", "createFinanceInvoice",
                FinanceActions.CREATE, CommandCatalog.FINANCE_POLICY);
        assertMapping("/api/v1/instructions/acknowledge", "acknowledgePublishedInstruction",
                InstructionFactActions.ACKNOWLEDGE, CommandCatalog.INSTRUCTION_POLICY);
    }

    @Test
    void realRoutesHaveIdsDistinctFromTheSyntheticBaseline() {
        assertEquals("acceptOrder", CATALOG.findByPath("POST", "/api/v1/orders/{id}/accept")
                .orElseThrow().id());
        assertEquals("issueInvoice", CATALOG.findByPath("POST", "/api/v1/invoices")
                .orElseThrow().id());
        assertNotEquals("acceptOrder", CATALOG.findByPath("POST", "/api/v1/xch03/orders/{id}/accept")
                .orElseThrow().id());
        assertNotEquals("issueInvoice", CATALOG.findByPath("POST", "/api/v1/finance/invoices")
                .orElseThrow().id());
    }

    @Test
    void normalizedTemplateMatchesButConcreteRequestIdentifierNeverSelectsACommand() {
        CommandDefinition definition = CATALOG.findByPath("POST", "/api/v1/xch03/orders/{orderId}/accept")
                .orElseThrow();
        assertEquals("acceptExchangeResponse", definition.id());
        assertEquals(definition.id(), CATALOG.findByPath("POST", "/api/v1/xch03/orders/{reference}/accept")
                .orElseThrow().id());
        assertTrue(CATALOG.findByPath("POST", "/api/v1/xch03/orders/123/accept").isEmpty());
    }

    @Test
    void nearMissAndWrongMethodRoutesFailClosed() {
        assertTrue(CATALOG.findByPath("POST", "/api/v1/xch03/orders/{id}/accept/decline").isEmpty());
        assertTrue(CATALOG.findByPath("GET", "/api/v1/finance/invoices").isEmpty());
        assertTrue(CATALOG.findByPath("POST", "/api/v1/critical-results/{id}/acknowledge/extra").isEmpty());
    }

    @Test
    void deliveredMappingsDoNotBroadenLiveGateRegistration() {
        assertEquals(24, CATALOG.paths().size());
        assertTrue(CATALOG.paths().contains("/api/v1/orders/{id}/accept"));
        assertTrue(CATALOG.paths().stream().noneMatch("/api/v1/xch03/orders/{id}/accept"::equals));
        assertTrue(CATALOG.paths().stream().noneMatch("/api/v1/finance/invoices"::equals));
    }

    @Test
    void mappingOnlyEntriesAreVisibleToReplayScopeAsWellAsLookup() throws Exception {
        // Replay authorization is an out-of-scope consumer of commands(). Read its
        // published route index reflectively so this slice proves the consequence of
        // adding a mapping without editing that class or its package tests.
        Field field = Class.forName("com.aurevia.web.DurableReplayAuthorization")
                .getDeclaredField("ROUTES");
        field.setAccessible(true);
        Object routes = field.get(null);
        assertTrue(routes instanceof Map<?, ?>);
        Map<?, ?> index = (Map<?, ?>) routes;

        assertTrue(index.containsKey("POST /api/v1/orders/{id}/accept"));
        assertTrue(index.containsKey("POST /api/v1/xch03/orders/{id}/accept"));
        assertTrue(index.containsKey("POST /api/v1/finance/invoices"));
        assertEquals(37, index.size(), "24 baseline, 10 mappings and 3 substitution routes");
    }

    @Test
    void realRouteDuplicateWithAnotherVariableNameIsRejected() {
        CommandDefinition first = new CommandDefinition("firstRealCommand", "EXCHANGE_ACCEPT", "POST",
                "/api/v1/xch03/orders/{id}/accept", CommandCatalog.EXCHANGE_RESPONSE_POLICY);
        CommandDefinition second = new CommandDefinition("secondRealCommand", "EXCHANGE_ACCEPT", "POST",
                "/api/v1/xch03/orders/{orderId}/accept", CommandCatalog.EXCHANGE_RESPONSE_POLICY);

        assertEquals("duplicate command route: POST /api/v1/xch03/orders/{}/accept",
                assertThrows(IllegalArgumentException.class,
                        () -> new CommandCatalog(List.of(first, second))).getMessage());
    }

    @Test
    void catalogKeepsTheSyntheticBaselineAndAddsEveryDeliveredMapping() {
        assertEquals(34, CATALOG.commands().size());
        for (String route : List.of("/api/v1/clinic/verifications",
                "/api/v1/clinic/appointments/{id}/check-in", "/api/v1/clinic/worklist/{id}/assign",
                "/api/v1/therapy/episodes", "/api/v1/xch03/orders/{id}/accept",
                "/api/v1/xch03/orders/{id}/reject", "/api/v1/releases",
                "/api/v1/critical-results/{id}/acknowledge", "/api/v1/finance/invoices",
                "/api/v1/instructions/acknowledge")) {
            assertEquals(1, CATALOG.commands().stream()
                    .filter(definition -> route.equals(definition.path())).count(), route);
        }
    }

    /** Assert one real handler template resolves to one command with the owning action and policy. */
    private static void assertMapping(String path, String id, String action, String policy) {
        CommandDefinition definition = CATALOG.findByPath("POST", path).orElseThrow();
        assertEquals(id, definition.id(), path);
        assertEquals(action, definition.action(), path);
        assertEquals(policy, definition.policy(), path);
        assertEquals("POST", definition.method(), path);
    }
}
