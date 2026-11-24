package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.aurevia.lab.accession.AccessionCommands.AccessionRequest;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.AccessionView;
import com.aurevia.lab.accession.AccessionCommands.CollectRequest;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabAccessionService;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.lab.accession.AccessionCommands.ReceiveRequest;
import com.aurevia.lab.accession.AccessionCommands.RejectRequest;
import com.aurevia.lab.accession.AccessionCommands.RecollectRequest;
import com.aurevia.tenant.TenantContext;

/** S-G2-08 LAB-01 service branches: identity matching, transitions, rejection and recollection. */
class LabAccessionServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "lab-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String NEW_ID = "44444444-4444-4444-4444-444444444444";
    private static final String COLLECTED_AT = "2026-11-19T10:15:30Z";
    private static final String RECEIVED_AT = "2026-11-19T11:15:30Z";

    private LabAccessionRepository repository;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private LabAccessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(LabAccessionRepository.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        service = new LabAccessionService(repository, new LabActionPolicy(), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "LAB_ACCESSION", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    private void authorizedCollector() {
        authorized("lab_collector");
    }

    private void authorizedVerifier() {
        authorized("lab_verifier");
    }

    private static AccessionRequest accessionRequest() {
        return new AccessionRequest("ACC-1", "LAB-A", PATIENT, "ORD/1");
    }

    private static CollectRequest collectRequest() {
        return new CollectRequest(PATIENT, "ACC-1", "LAB-A", "PLASMA", "SITE-A",
                "Sam Collector", COLLECTED_AT, 1);
    }

    private static ReceiveRequest receiveRequest() {
        return new ReceiveRequest(PATIENT, "ACC-1", "LAB-A", "acceptable", RECEIVED_AT, 2);
    }

    private static RejectRequest rejectRequest() {
        return new RejectRequest("hemolysed sample", "lab-a@example.test", 3);
    }

    private static RecollectRequest recollectRequest() {
        return new RecollectRequest("ACC-2", "LAB-A", 4);
    }

    private static com.aurevia.lab.accession.LabAccession accession(AccessionState state, int version) {
        return new com.aurevia.lab.accession.LabAccession(ID, version, "ACC-1", "LAB-A",
                PATIENT, "ORD/1", state, "PLASMA", "SITE-A", "Sam Collector",
                COLLECTED_AT,
                state == AccessionState.RECEIVED || state == AccessionState.REJECTED
                        || state == AccessionState.RECOLLECTED ? RECEIVED_AT : null,
                state == AccessionState.RECEIVED || state == AccessionState.REJECTED
                        || state == AccessionState.RECOLLECTED ? "acceptable" : null,
                state == AccessionState.REJECTED || state == AccessionState.RECOLLECTED
                        ? "hemolysed sample" : null,
                state == AccessionState.REJECTED || state == AccessionState.RECOLLECTED
                        ? "lab-a@example.test" : null,
                null);
    }

    @Test
    void accessionInsertsAuditsAndReturnsTheStoredProjection() {
        authorizedCollector();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.findById(anyString())).thenReturn(
                Optional.of(accession(AccessionState.ACCESSIONED, 1)));
        assertEquals(AccessionState.ACCESSIONED, service.accession(accessionRequest()).state());
        verify(repository).insert(eq(ORG), anyString(), eq("ACC-1"), eq("LAB-A"), eq(PATIENT),
                eq("ORD/1"), eq(AccessionState.ACCESSIONED), eq(null));
        verify(repository).appendEvent(anyString(), eq(1), eq("accessioned"), eq(null),
                eq(AccessionState.ACCESSIONED), eq(null), eq(null));
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.ACCESSION_CREATE),
                eq("ACCESSION"), eq("LAB"), anyString(), eq("SUCCESS"));
    }

    @Test
    void accessionRejectsDuplicateIssuerScopedNumber() {
        authorizedCollector();
        when(repository.accessionExists("LAB-A", "ACC-1")).thenReturn(true);
        ApiException failure = assertThrows(ApiException.class, () -> service.accession(accessionRequest()));
        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        verify(repository, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        verify(audit).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("CONFLICT"));
    }

    @Test
    void accessionReportsAnUnknownPatientAsConcealed() {
        authorizedCollector();
        when(repository.patientExists(PATIENT)).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class, () -> service.accession(accessionRequest())).status());
    }

    @Test
    void accessionSurvivesAnInsertRaceAsConflict() {
        authorizedCollector();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.accession(accessionRequest())).status());
    }

    @Test
    void accessionInsertRaceIsAuditedAsConflict() {
        authorizedCollector();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.accession(accessionRequest())).status());
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.ACCESSION_CREATE),
                eq("ACCESSION"), eq("LAB"), anyString(), eq("CONFLICT"));
    }

    @Test
    void accessionDeniesInactiveMembershipAndWrongRole() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.accession(accessionRequest())).status());
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class, () -> service.accession(accessionRequest())).status());
        verify(audit, org.mockito.Mockito.times(2)).append(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq("DENIED"));
    }

    @Test
    void collectRecordsTheSpecimenAndItsMatch() {
        authorizedCollector();
        when(repository.findById(ID)).thenReturn(
                Optional.of(accession(AccessionState.ACCESSIONED, 1)),
                Optional.of(accession(AccessionState.COLLECTED, 2)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        assertEquals(AccessionState.COLLECTED, service.collect(ID, collectRequest()).state());
        verify(repository).update(eq(ORG), eq(ID), eq(1), eq(AccessionState.COLLECTED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq(COLLECTED_AT),
                eq(null), eq(null), eq(null), eq(null));
        verify(repository).appendEvent(ID, 2, "collected", AccessionState.ACCESSIONED,
                AccessionState.COLLECTED, null, PATIENT);
    }

    @Test
    void collectRefusesWrongPatientOrWrongAccession() {
        authorizedCollector();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.ACCESSIONED, 1)));
        ApiException patient = assertThrows(ApiException.class, () -> service.collect(ID,
                new CollectRequest("55555555-5555-5555-5555-555555555555", "ACC-1", "LAB-A",
                        "PLASMA", "SITE-A", "Sam Collector", COLLECTED_AT, 1)));
        assertEquals(422, patient.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", patient.code());
        ApiException accession = assertThrows(ApiException.class, () -> service.collect(ID,
                new CollectRequest(PATIENT, "ACC-9", "LAB-A", "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, 1)));
        assertEquals(422, accession.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", accession.code());
        ApiException issuer = assertThrows(ApiException.class, () -> service.collect(ID,
                new CollectRequest(PATIENT, "ACC-1", "LAB-B", "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, 1)));
        assertEquals(422, issuer.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", issuer.code());
        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void collectEnforcesStateVersionAndLostUpdate() {
        authorizedCollector();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.COLLECTED, 1)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.collect(ID, collectRequest())).status());

        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.ACCESSIONED, 2)));
        assertEquals(412, assertThrows(ApiException.class, () -> service.collect(ID, collectRequest())).status());

        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.ACCESSIONED, 1)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class, () -> service.collect(ID, collectRequest())).status());
    }

    @Test
    void receiveRecordsReceiptQualityAndItsMatch() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(
                Optional.of(accession(AccessionState.COLLECTED, 2)),
                Optional.of(accession(AccessionState.RECEIVED, 3)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        assertEquals(AccessionState.RECEIVED, service.receive(ID, receiveRequest()).state());
        verify(repository).update(eq(ORG), eq(ID), eq(2), eq(AccessionState.RECEIVED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq(COLLECTED_AT),
                eq(RECEIVED_AT), eq("acceptable"), eq(null), eq(null));
        verify(repository).appendEvent(ID, 3, "received", AccessionState.COLLECTED,
                AccessionState.RECEIVED, null, PATIENT);
    }

    @Test
    void receiveRefusesWrongPatientOrWrongAccession() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.COLLECTED, 2)));
        assertEquals("PATIENT_ACCESSION_MISMATCH", assertThrows(ApiException.class, () -> service.receive(ID,
                new ReceiveRequest("55555555-5555-5555-5555-555555555555", "ACC-1", "LAB-A",
                        "acceptable", RECEIVED_AT, 2))).code());
        assertEquals("PATIENT_ACCESSION_MISMATCH", assertThrows(ApiException.class, () -> service.receive(ID,
                new ReceiveRequest(PATIENT, "ACC-1", "LAB-B", "acceptable", RECEIVED_AT, 2))).code());
        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void receiveEnforcesStateVersionAndLostUpdate() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.ACCESSIONED, 2)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.receive(ID, receiveRequest())).status());
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.COLLECTED, 3)));
        assertEquals(412, assertThrows(ApiException.class, () -> service.receive(ID, receiveRequest())).status());
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.COLLECTED, 2)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class, () -> service.receive(ID, receiveRequest())).status());
    }

    @Test
    void rejectStoresReasonNotificationAndAudits() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(
                Optional.of(accession(AccessionState.RECEIVED, 3)),
                Optional.of(accession(AccessionState.REJECTED, 4)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        assertEquals(AccessionState.REJECTED, service.reject(ID, rejectRequest()).state());
        verify(repository).update(eq(ORG), eq(ID), eq(3), eq(AccessionState.REJECTED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq(COLLECTED_AT),
                eq(RECEIVED_AT), eq("acceptable"), eq("hemolysed sample"), eq("lab-a@example.test"));
        verify(repository).appendEvent(ID, 4, "rejected", AccessionState.RECEIVED,
                AccessionState.REJECTED, "hemolysed sample", null);
    }

    @Test
    void rejectFromCollectedIsAllowedButOtherStatesAreNot() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(
                Optional.of(accession(AccessionState.COLLECTED, 2)),
                Optional.of(accession(AccessionState.REJECTED, 3)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        assertEquals(AccessionState.REJECTED, service.reject(ID,
                new RejectRequest("hemolysed sample", "lab-a@example.test", 2)).state());
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.ACCESSIONED, 1)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.reject(ID,
                new RejectRequest("hemolysed sample", "lab-a@example.test", 1))).status());
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.REJECTED, 4)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.reject(ID,
                new RejectRequest("hemolysed sample", "lab-a@example.test", 4))).status());
    }

    @Test
    void rejectEnforcesVersionAndLostUpdate() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.RECEIVED, 4)));
        assertEquals(412, assertThrows(ApiException.class, () -> service.reject(ID, rejectRequest())).status());
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.RECEIVED, 3)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class, () -> service.reject(ID, rejectRequest())).status());
    }

    @Test
    void recollectCreatesALinkedSpecimenOnTheSameOrder() {
        authorizedVerifier();
        when(repository.findById(anyString())).thenReturn(
                Optional.of(accession(AccessionState.REJECTED, 4)),
                Optional.of(new com.aurevia.lab.accession.LabAccession(NEW_ID, 1,
                        "ACC-2", "LAB-A", PATIENT, "ORD/1", AccessionState.ACCESSIONED,
                        null, null, null, null, null, null, null, null, ID)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        assertEquals(ID, service.recollect(ID, recollectRequest()).replacesAccessionId());
        verify(repository).insert(eq(ORG), anyString(), eq("ACC-2"), eq("LAB-A"), eq(PATIENT),
                eq("ORD/1"), eq(AccessionState.ACCESSIONED), eq(ID));
        verify(repository).update(eq(ORG), eq(ID), eq(4), eq(AccessionState.RECOLLECTED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq(COLLECTED_AT),
                eq(RECEIVED_AT), eq("acceptable"), eq("hemolysed sample"), eq("lab-a@example.test"));
        verify(repository).appendEvent(ID, 5, "recollected", AccessionState.REJECTED,
                AccessionState.RECOLLECTED, null, null);
        verify(repository).appendEvent(anyString(), eq(1), eq("accessioned"), eq(null),
                eq(AccessionState.ACCESSIONED), eq(null), eq(null));
    }

    @Test
    void recollectRejectsWrongStateDuplicateNumberStaleVersionAndLostUpdate() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.RECEIVED, 3)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.recollect(ID,
                new RecollectRequest("ACC-2", "LAB-A", 3))).status());

        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.REJECTED, 4)));
        when(repository.accessionExists("LAB-A", "ACC-2")).thenReturn(true);
        assertEquals(409, assertThrows(ApiException.class, () -> service.recollect(ID, recollectRequest())).status());

        when(repository.accessionExists("LAB-A", "ACC-2")).thenReturn(false);
        assertEquals(412, assertThrows(ApiException.class, () -> service.recollect(ID,
                new RecollectRequest("ACC-2", "LAB-A", 3))).status());

        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class, () -> service.recollect(ID, recollectRequest())).status());
    }

    @Test
    void recollectSurvivesAnInsertRaceAsConflict() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.REJECTED, 4)));
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.recollect(ID, recollectRequest())).status());
        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void recollectInsertRaceIsAuditedAsConflict() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.REJECTED, 4)));
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.recollect(ID, recollectRequest())).status());
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.SPECIMEN_RECOLLECT),
                eq("ACCESSION"), eq("LAB"), eq(ID), eq("CONFLICT"));
    }

    @Test
    void getReturnsStoredProjectionWithHistory() {
        authorizedCollector();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.RECEIVED, 3)));
        AccessionEvent event = new AccessionEvent("e1", ID, 1, "accessioned", null,
                AccessionState.ACCESSIONED.wire(), null, PATIENT, "2026-11-19T09:00:00Z");
        when(repository.history(ID)).thenReturn(List.of(event));
        AccessionView view = service.get(ID);
        assertEquals(AccessionState.RECEIVED, view.accession().state());
        assertEquals(1, view.events().size());
        assertEquals("accessioned", view.events().get(0).action());
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.ACCESSION_READ),
                eq("ACCESSION"), eq("LAB"), eq(ID), eq("SUCCESS"));
    }

    @Test
    void transitionsRecollectAndReadDenyWithoutMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.collect(ID, collectRequest())).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.reject(ID, rejectRequest())).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.recollect(ID, recollectRequest())).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(ID)).status());
        verify(audit, org.mockito.Mockito.times(4)).append(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq("DENIED"));
    }

    @Test
    void transitionAndRecollectionConcealUnknownAccessions() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_collector", "lab_verifier"));
        when(repository.findById(ID)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.collect(ID, collectRequest())).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.recollect(ID, recollectRequest())).status());
    }

    @Test
    void getConcealsUnknownAccession() {
        authorizedCollector();
        when(repository.findById(ID)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(ID)).status());
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.ACCESSION_READ),
                eq("ACCESSION"), eq("LAB"), eq(ID), eq("NOT_FOUND"));
    }
}
