package com.aurevia.labflow.rejection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionEvent;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.tenant.TenantContext;

/** LAB-01 specimen rejection, clinician notification and linked recollection invariants. */
class SpecimenRejectionServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "verifier-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String NUMBER = "ACC-1";
    private static final String ISSUER = "LAB-A";
    private static final String ORDER = "ORD/1";
    private static final String CLINICIAN = "clinician-a";
    private static final String PURPOSE = "LAB_SPECIMEN_REJECTION";
    private static final String RESOURCE = "SPECIMEN";
    private static final String SCOPE = "LAB";
    private static final String NOTIFY_ACTION = "LAB_SPECIMEN_REJECTION_NOTIFY";

    private LabAccessionRepository accessions;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private RejectionNotificationPolicy notificationPolicy;
    private SpecimenRejectionService service;

    @BeforeEach
    void setUp() {
        accessions = mock(LabAccessionRepository.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        notificationPolicy = new RejectionNotificationPolicy(true);
        service = new SpecimenRejectionService(accessions, new LabActionPolicy(), notificationPolicy,
                transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, SUBJECT, PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorizedVerifier() {
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(accessions.activeRoles(SUBJECT)).thenReturn(List.of("lab_verifier"));
    }

    private static LabAccession specimen(AccessionState state, int version) {
        return new LabAccession(ID, version, NUMBER, ISSUER, PATIENT, ORDER, state,
                "PLASMA", "SITE-A", "Sam Collector", "2026-11-19T10:15:30Z", null, "acceptable",
                null, null, null);
    }

    private static LabAccession rejectedSpecimen(int version) {
        return new LabAccession(ID, version, NUMBER, ISSUER, PATIENT, ORDER, AccessionState.REJECTED,
                "PLASMA", "SITE-A", "Sam Collector", "2026-11-19T10:15:30Z", null, "acceptable",
                "haemolysed", CLINICIAN, null);
    }

    private static RejectSpecimenRequest reject(String reason, String detail, boolean notifyPatient) {
        return new RejectSpecimenRequest(reason, detail, CLINICIAN, notifyPatient, 1);
    }

    private static RecollectSpecimenRequest recollect() {
        return new RecollectSpecimenRequest("ACC-2", ISSUER, 2);
    }

    // ---- reject: reason and notification -------------------------------------

    @Test
    void rejectKeepsReasonAndNotifiesOrderingClinicianAndPatient() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(
                Optional.of(specimen(AccessionState.RECEIVED, 1)),
                Optional.of(rejectedSpecimen(2)));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);

        RejectionOutcome outcome = service.reject(ID, reject("haemolysed", null, true));

        assertEquals(AccessionState.REJECTED, outcome.state());
        assertEquals(2, outcome.version());
        assertEquals("haemolysed", outcome.reason());
        assertEquals(CLINICIAN, outcome.notification());
        assertTrue(outcome.clinicianNotified());
        assertTrue(outcome.patientNotified());
        verify(accessions).update(eq(ORG), eq(ID), eq(1), eq(AccessionState.REJECTED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq("2026-11-19T10:15:30Z"),
                eq(null), eq("acceptable"), eq("haemolysed"), eq(CLINICIAN));
        verify(accessions).appendEvent(ID, 2, "rejected", AccessionState.RECEIVED,
                AccessionState.REJECTED, "haemolysed", null);
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_REJECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("SUCCESS"));
        verify(audit).append(any(), eq(PURPOSE), eq(NOTIFY_ACTION), eq("NOTIFICATION"),
                eq(SCOPE), eq("clinician:" + CLINICIAN), eq("SUCCESS"));
        verify(audit).append(any(), eq(PURPOSE), eq(NOTIFY_ACTION), eq("NOTIFICATION"),
                eq(SCOPE), eq("patient:" + PATIENT), eq("SUCCESS"));
    }

    @Test
    void allFiveBoundedReasonCodesAreAccepted() {
        String[] wires = {"haemolysed", "wrong_tube", "unlabelled", "insufficient_volume", "other"};

        for (String wire : wires) {
            RejectionReason reason = RejectionReason.fromWire(wire);
            assertEquals(wire, reason.wire());
            assertEquals("other".equals(wire), reason.requiresDetail());
        }
    }

    @Test
    void rejectRequiresAReason() {
        ApiException missing = assertThrows(ApiException.class, () -> service.reject(ID, reject(null, null, false)));
        ApiException blank = assertThrows(ApiException.class, () -> service.reject(ID, reject("   ", null, false)));

        assertEquals(422, missing.status());
        assertEquals("REASON_REQUIRED", missing.code());
        assertEquals("REASON_REQUIRED", blank.code());
        verify(accessions, never()).findById(anyString());
    }

    @Test
    void rejectRejectsAnUnrecognisedReason() {
        ApiException failure = assertThrows(ApiException.class,
                () -> service.reject(ID, reject("bogus", null, false)));

        assertEquals(422, failure.status());
        assertEquals("INVALID_REASON", failure.code());
    }

    @Test
    void rejectOtherReasonRequiresBoundedDetail() {
        ApiException missing = assertThrows(ApiException.class, () -> service.reject(ID, reject("other", null, false)));
        ApiException malformed = assertThrows(ApiException.class,
                () -> service.reject(ID, reject("other", "<script>", false)));

        assertEquals("DETAIL_REQUIRED", missing.code());
        assertEquals("DETAIL_REQUIRED", malformed.code());
    }

    @Test
    void rejectOtherReasonRetainsItsDetail() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.RECEIVED, 1)));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);

        RejectionOutcome outcome = service.reject(ID, reject("other", "tube degraded in transit", false));

        assertEquals("other: tube degraded in transit", outcome.reason());
        assertEquals(CLINICIAN, outcome.notification());
        assertFalse(outcome.patientNotified());
        verify(accessions).appendEvent(ID, 2, "rejected", AccessionState.RECEIVED,
                AccessionState.REJECTED, "other: tube degraded in transit", null);
    }

    @Test
    void rejectDoesNotNotifyPatientWhenPolicyDisallows() {
        notificationPolicy = new RejectionNotificationPolicy(false);
        service = new SpecimenRejectionService(accessions, new LabActionPolicy(), notificationPolicy,
                transactions, audit);
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.RECEIVED, 1)));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);

        RejectionOutcome outcome = service.reject(ID, reject("haemolysed", null, true));

        assertFalse(outcome.patientNotified());
        assertTrue(outcome.clinicianNotified());
        verify(audit, times(1)).append(any(), eq(PURPOSE), eq(NOTIFY_ACTION), eq("NOTIFICATION"),
                eq(SCOPE), eq("clinician:" + CLINICIAN), eq("SUCCESS"));
        verify(audit, never()).append(any(), eq(PURPOSE), eq(NOTIFY_ACTION), eq("NOTIFICATION"),
                eq(SCOPE), eq("patient:" + PATIENT), eq("SUCCESS"));
    }

    @Test
    void rejectDeniesInactiveMembershipAndWrongRole() {
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reject(ID, reject("haemolysed", null, false))).status());

        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(accessions.activeRoles(SUBJECT)).thenReturn(List.of("lab_collector"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reject(ID, reject("haemolysed", null, false))).status());

        verify(audit, times(2)).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_REJECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("DENIED"));
    }

    @Test
    void rejectConcealsAnUnknownSpecimenAsNotFound() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reject(ID, reject("haemolysed", null, false)));

        assertEquals(404, failure.status());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_REJECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("NOT_FOUND"));
    }

    @Test
    void rejectRefusesStaleVersionIllegalStateAndLostUpdate() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.RECEIVED, 2)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.reject(ID, reject("haemolysed", null, false))).status());

        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.ACCESSIONED, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.reject(ID, reject("haemolysed", null, false))).status());

        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.RECEIVED, 1)));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.reject(ID, reject("haemolysed", null, false))).status());
    }

    // ---- recollection --------------------------------------------------------

    @Test
    void recollectCreatesLinkedSpecimenOnTheSameOrder() {
        authorizedVerifier();
        when(accessions.findById(anyString())).thenAnswer(invocation -> {
            String requested = invocation.getArgument(0);
            if (ID.equals(requested)) {
                return Optional.of(rejectedSpecimen(2));
            }
            return Optional.of(new LabAccession(requested, 1, "ACC-2", ISSUER, PATIENT, ORDER,
                    AccessionState.ACCESSIONED, null, null, null, null, null, null,
                    null, null, ID));
        });
        when(accessions.accessionExists(ISSUER, "ACC-2")).thenReturn(false);
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);

        RecollectionOutcome outcome = service.recollect(ID, recollect());

        assertTrue(!outcome.specimenId().isEmpty());
        assertTrue(!outcome.specimenId().equals(ID));
        assertEquals(ID, outcome.replacesAccessionId());
        assertEquals(ORDER, outcome.sourceOrderRef());
        assertEquals(AccessionState.ACCESSIONED, outcome.state());
        verify(accessions).insert(eq(ORG), eq(outcome.specimenId()), eq("ACC-2"), eq(ISSUER), eq(PATIENT),
                eq(ORDER), eq(AccessionState.ACCESSIONED), eq(ID));
        verify(accessions).update(eq(ORG), eq(ID), eq(2), eq(AccessionState.RECOLLECTED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq("2026-11-19T10:15:30Z"),
                eq(null), eq("acceptable"), eq("haemolysed"), eq(CLINICIAN));
        verify(accessions).appendEvent(ID, 3, "recollected", AccessionState.REJECTED,
                AccessionState.RECOLLECTED, null, null);
        verify(accessions).appendEvent(outcome.specimenId(), 1, "accessioned", null,
                AccessionState.ACCESSIONED, null, null);
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_RECOLLECT),
                eq(RESOURCE), eq(SCOPE), eq(outcome.specimenId()), eq("SUCCESS"));
    }

    @Test
    void recollectConcealsAnUnknownSpecimenAsNotFound() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.empty());

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.recollect(ID, recollect())).status());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_RECOLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("NOT_FOUND"));
    }

    @Test
    void recollectDeniesInactiveMembershipAndWrongRole() {
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.recollect(ID, recollect())).status());

        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(accessions.activeRoles(SUBJECT)).thenReturn(List.of("lab_collector"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.recollect(ID, recollect())).status());

        verify(audit, times(2)).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_RECOLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("DENIED"));
    }

    @Test
    void recollectRefusesStaleVersionAndNonRejectedState() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.of(rejectedSpecimen(3)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.recollect(ID, recollect())).status());

        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.RECEIVED, 2)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.recollect(ID, recollect())).status());
    }

    @Test
    void recollectRefusesADuplicateAccessionNumber() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.of(rejectedSpecimen(2)));
        when(accessions.accessionExists(ISSUER, "ACC-2")).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class, () -> service.recollect(ID, recollect()));

        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        verify(accessions, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void recollectRefusesAConcurrentDuplicateInsert() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.of(rejectedSpecimen(2)));
        when(accessions.accessionExists(ISSUER, "ACC-2")).thenReturn(false);
        doAnswer(invocation -> {
            throw new DuplicateKeyException("duplicate");
        }).when(accessions).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());

        ApiException failure = assertThrows(ApiException.class, () -> service.recollect(ID, recollect()));

        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        verify(accessions, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
        // A 23505 poisons the PostgreSQL transaction: no audit insert can follow it here.
        verify(audit, never()).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_RECOLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("CONFLICT"));
    }

    @Test
    void recollectRefusesALostUpdate() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.of(rejectedSpecimen(2)));
        when(accessions.accessionExists(ISSUER, "ACC-2")).thenReturn(false);
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);

        assertEquals(412, assertThrows(ApiException.class,
                () -> service.recollect(ID, recollect())).status());
    }

    // ---- history -------------------------------------------------------------

    @Test
    void historyReturnsTheRejectedSpecimensImmutableEvents() {
        authorizedVerifier();
        AccessionEvent event = new AccessionEvent("e1", ID, 2, "rejected",
                AccessionState.RECEIVED.wire(), AccessionState.REJECTED.wire(), "haemolysed", null,
                "2026-11-19T11:00:00Z");
        when(accessions.findById(ID)).thenReturn(Optional.of(rejectedSpecimen(2)));
        when(accessions.history(ID)).thenReturn(List.of(event));

        List<AccessionEvent> events = service.history(ID);

        assertEquals(List.of(event), events);
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_READ),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("SUCCESS"));
    }

    @Test
    void historyConcealsAnUnknownSpecimenAsNotFound() {
        authorizedVerifier();
        when(accessions.findById(ID)).thenReturn(Optional.empty());

        assertEquals(404, assertThrows(ApiException.class, () -> service.history(ID)).status());
        verify(accessions, never()).history(anyString());
    }

    @Test
    void historyDeniesWithoutActiveMembership() {
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(false);

        assertEquals(403, assertThrows(ApiException.class, () -> service.history(ID)).status());
        verify(accessions, never()).findById(anyString());
    }
}
