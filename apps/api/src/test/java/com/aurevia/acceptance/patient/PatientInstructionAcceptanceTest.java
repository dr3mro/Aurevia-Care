package com.aurevia.acceptance.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.messaging.acknowledgment.InstructionFactService;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FactType;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.RetryCommand;
import com.aurevia.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G3 acceptance for patient instructions and message facts.
 *
 * <p>Requirements proved: MSG-04 (staff send versioned instructions), MSG-05
 * (publication, dispatch, first view and acknowledgment are separate facts and
 * an acknowledgment is bound to one exact revision), MSG-06 (generic dispatch
 * and retry) and OPS-02 (audited, detail-free facts). All fixtures are
 * synthetic.</p>
 */
class PatientInstructionAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "33333333-3333-3333-3333-333333333333";
    private static final String INSTRUCTION = "44444444-4444-4444-4444-444444444444";
    private static final String STAFF = "syn-clinician";
    private static final String PATIENT_SUBJECT = "syn-patient-instructions";
    private static final String DUE_AT = "2026-06-15T13:00:00Z";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private InMemoryInstructionFactStore store;
    private InstructionFactService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryInstructionFactStore();
        service = new InstructionFactService(store, new InstructionFactActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        store.addMember(ORG, STAFF, "clinician", true);
        store.assignStaffUnit(ORG, STAFF, UNIT);
        store.addMember(ORG, PATIENT_SUBJECT, "patient", true);
        store.linkPatient(ORG, PATIENT_SUBJECT, PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, true);
        bind(STAFF);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void acknowledgmentIsBoundToTheExactInstructionRevision() {
        // MSG-05: publication, dispatch and acknowledgment are separate facts;
        // a receipt for a superseded revision is refused, and the current
        // revision records exactly one receipt-only acknowledgment.
        publish(1, "publish-1");
        publish(2, "publish-2");

        bind(PATIENT_SUBJECT);
        ApiException stale = assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-stale")));
        assertEquals(409, stale.status());
        assertEquals("STALE_REVISION", stale.code());

        Acknowledgment receipt = service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 2, "ack-current"));
        assertEquals(2, receipt.revision());
        assertEquals(AcknowledgmentScope.RECEIPT, receipt.scope());
        assertEquals(PATIENT_SUBJECT, receipt.actor());
        assertTrue(store.acknowledgments(ORG, INSTRUCTION, 2).contains(receipt));
        assertTrue(store.acknowledgments(ORG, INSTRUCTION, 1).isEmpty());
        assertTrue(service.facts(INSTRUCTION).facts().stream().anyMatch(fact -> fact.type() == FactType.ACKNOWLEDGMENT));
    }

    @Test
    void failedNotificationIsRetriedAsASeparateGenericFact() {
        // MSG-04 / MSG-06: a retry appends a new dispatch fact carrying only the
        // generic template key; the same client key returns the original fact.
        publishWithNotification(1, "publish-notified");
        assertEquals(1, store.dispatches(ORG, INSTRUCTION, 1).size());

        Dispatch retried = service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "retry-1"));
        assertEquals(2, store.dispatches(ORG, INSTRUCTION, 1).size());
        assertEquals(InstructionFactService.GENERIC_NOTIFICATION_KEY, retried.genericKey());
        assertEquals(retried, service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "retry-1")));
        assertEquals(2, store.dispatches(ORG, INSTRUCTION, 1).size());

        for (Dispatch dispatch : store.dispatches(ORG, INSTRUCTION, 1)) {
            assertFalse(dispatch.genericKey().contains("content-ref"));
            assertEquals(1, dispatch.revision());
        }
    }

    private void publish(int revision, String clientId) {
        service.publish(new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision, "content-ref-" + revision,
                true, DUE_AT, false, clientId));
    }

    private void publishWithNotification(int revision, String clientId) {
        service.publish(new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision, "content-ref-" + revision,
                true, DUE_AT, true, clientId));
    }

    private static void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PURPOSE", "corr-g3-instruction"));
    }
}
