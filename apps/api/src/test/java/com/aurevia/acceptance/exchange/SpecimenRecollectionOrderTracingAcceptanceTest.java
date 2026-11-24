package com.aurevia.acceptance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionEvent;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.labflow.rejection.RecollectSpecimenRequest;
import com.aurevia.labflow.rejection.RecollectionOutcome;
import com.aurevia.labflow.rejection.RejectSpecimenRequest;
import com.aurevia.labflow.rejection.RejectionNotificationPolicy;
import com.aurevia.labflow.rejection.RejectionOutcome;
import com.aurevia.labflow.rejection.SpecimenRejectionService;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G2 acceptance for a rejected and recollected specimen (LAB-01, G2).
 *
 * <p>A rejected specimen keeps its reason and notification, becomes terminal and
 * spawns one linked recollection that inherits the same patient and source order,
 * so both specimens trace to the right order and the immutable history proves the
 * chain. The repository mock holds only synthetic domain state; no patient data
 * or credential is used.</p>
 */
class SpecimenRecollectionOrderTracingAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "labverifier-g2";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String NUMBER = "ACC-G2-1";
    private static final String NEW_NUMBER = "ACC-G2-2";
    private static final String ISSUER = "LAB-G2";
    private static final String ORDER = "ORD/42";
    private static final String CLINICIAN = "clinician-g2";

    private LabAccessionRepository accessions;
    private AuditAppender audit;
    private SpecimenRejectionService service;

    @BeforeEach
    void setUp() {
        accessions = mock(LabAccessionRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new SpecimenRejectionService(accessions, new LabActionPolicy(),
                new RejectionNotificationPolicy(true), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "corr-g2-specimen"));
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(accessions.activeRoles(SUBJECT)).thenReturn(List.of("lab_verifier"));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyString())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectedThenRecollectedSpecimenTracesToTheRightOrder() {
        // LAB-01 / G2: the rejected specimen is terminal and the new specimen
        // inherits its patient and source order, so both stay on one order.
        when(accessions.findById(ID)).thenReturn(
                Optional.of(specimen(AccessionState.RECEIVED, 1, null, null)),
                Optional.of(specimen(AccessionState.REJECTED, 2, "haemolysed", CLINICIAN)),
                Optional.of(specimen(AccessionState.RECOLLECTED, 3, "haemolysed", CLINICIAN)));
        when(accessions.accessionExists(ISSUER, NEW_NUMBER)).thenReturn(false);

        RejectionOutcome rejection = service.reject(ID,
                new RejectSpecimenRequest("haemolysed", null, CLINICIAN, true, 1));
        assertEquals(AccessionState.REJECTED, rejection.state());
        assertEquals(2, rejection.version());
        assertEquals("haemolysed", rejection.reason());
        assertEquals(CLINICIAN, rejection.notification());
        assertTrue(rejection.clinicianNotified());
        assertTrue(rejection.patientNotified());
        verify(accessions).appendEvent(ID, 2, "rejected", AccessionState.RECEIVED,
                AccessionState.REJECTED, "haemolysed", null);

        RecollectionOutcome recollection = service.recollect(ID,
                new RecollectSpecimenRequest(NEW_NUMBER, ISSUER, 2));
        assertNotEquals(ID, recollection.specimenId(), "a recollection mints a distinct specimen id");
        assertEquals(ID, recollection.replacesAccessionId());
        assertEquals(ORDER, recollection.sourceOrderRef(), "the recollection traces to the same order");
        assertEquals(AccessionState.ACCESSIONED, recollection.state());
        assertEquals(1, recollection.version());
        verify(accessions).insert(eq(ORG), anyString(), eq(NEW_NUMBER), eq(ISSUER), eq(PATIENT),
                eq(ORDER), eq(AccessionState.ACCESSIONED), eq(ID));
        verify(accessions).appendEvent(ID, 3, "recollected", AccessionState.REJECTED,
                AccessionState.RECOLLECTED, null, null);

        when(accessions.history(ID)).thenReturn(List.of(
                event(ID, 2, "rejected", "rejected"),
                event(ID, 3, "recollected", null)));
        List<AccessionEvent> history = service.history(ID);
        assertEquals(2, history.size());
        assertEquals("rejected", history.get(0).action());
        assertEquals("recollected", history.get(1).action());
        assertEquals("rejected", history.get(0).reason(), "the retained reason stays in the trace");
        assertNull(history.get(1).reason(), "the recollection event carries no new reason");
    }

    private static LabAccession specimen(AccessionState state, int version, String reason,
                                         String notification) {
        return new LabAccession(ID, version, NUMBER, ISSUER, PATIENT, ORDER, state,
                "PLASMA", "SITE-A", "Sam Collector", "2026-11-21T09:00:00Z", null,
                "acceptable", reason, notification, null);
    }

    private static AccessionEvent event(String accessionId, int version, String action, String reason) {
        return new AccessionEvent("event-" + version, accessionId, version, action, null, null,
                reason, null, "2026-11-21T09:00:00Z");
    }
}
