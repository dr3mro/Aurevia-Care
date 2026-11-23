package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.order.OrderRepository;
import com.aurevia.smartclinic.exchange.order.OrderService;
import com.aurevia.smartclinic.exchange.order.VerifyPatientRequest;
import com.aurevia.smartclinic.exchange.order.WorkItemView;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * XCH-01 clause 4 proof: the receiving organization sees an exchange-scoped work
 * item, not a sender chart, and binds a locally verified patient to it. The
 * positive cases assert the bounded projection and the one-way local
 * verification with their audit rows. Negative cases show that the service
 * refuses the wrong role, a subject without active membership, a caller outside
 * the destination tenant, an unmatched or malformed local identity and a repeat
 * verification. All data is synthetic.
 *
 * <p>The real-PostgreSQL proof of the RLS/FORCE RLS policy, the two-party row
 * policy and the receiver-update guard is the existing
 * {@code it.exchangeorder.OrderExchangeIntegrationTest}; this class is the
 * clause-focused service-level evidence with a mocked repository.</p>
 */
class ReceiverSeesExchangeSpecificWorkItemLocallyTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String LOCAL_PATIENT = "44444444-4444-4444-4444-444444444444";
    private static final String WORK_ITEM = "77777777-7777-7777-7777-777777777777";
    private static final String ACTOR = "clinician-b";
    private static final String OTHER_ACTOR = "clinician-a";
    private static final String FINGERPRINT = "f1";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        // The service re-derives membership and role inside one guarded transaction.
        // Run the callback inline so the assertions observe the transactional result.
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new OrderService(repository, audit, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // The receiver's context is derived from authentication and the selected
        // organization; it is never inferred from a resource identifier.
        TenantContext.set(new TenantContext(DEST, ACTOR, "EXCHANGE", "corr-clause"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant one active role to the receiving clinician in the destination organization. */
    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    /** The standard locally verified identity presented at the receiving organization. */
    private static VerifyPatientRequest verifyRequest() {
        return new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", "CLINIC", "site-verified MRN match");
    }

    /** A synthetic bounded projection of the sender's released order. */
    private static WorkItemView projectedItem(boolean verified) {
        return new WorkItemView(WORK_ITEM, ORG, DEST, ORG, PATIENT, "MRN-1", "CLINIC",
                verified ? LOCAL_PATIENT : null, verified, "routine", "Synthetic CBC question",
                "lab-queue", FINGERPRINT, verified ? "ready" : "awaiting_local_patient", NOW);
    }

    /** Capture the structured denial from a command that must be refused. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that a boundary validation failure happens before any command runs. */
    private static ApiException invalid(Runnable action) {
        ApiException failure = reject(action);
        assertEquals(422, failure.status());
        assertEquals("INVALID_INPUT", failure.code());
        return failure;
    }

    @Test
    void receiverSeesOnlyTheExchangeScopedWorkItem() {
        authorized("clinician");
        WorkItemView item = projectedItem(false);
        when(repository.workItems()).thenReturn(List.of(item));

        List<WorkItemView> items = service.workItems();

        assertEquals(1, items.size());
        WorkItemView seen = items.get(0);
        assertEquals(WORK_ITEM, seen.id());
        assertEquals(DEST, seen.destinationOrg());
        assertEquals(ORG, seen.senderOrg());
        assertEquals(ORG, seen.orderRef());
        assertEquals(PATIENT, seen.patientRef());
        assertEquals(FINGERPRINT, seen.fingerprint());
        assertEquals("awaiting_local_patient", seen.state());
        assertFalse(seen.patientVerified());
        assertNull(seen.localPatientId(), "no receiver chart key is bound before verification");
        assertEquals("Synthetic CBC question", seen.clinicalQuestion());
        assertEquals("lab-queue", seen.workQueue());
        verify(repository).workItems();
        verify(repository, never()).find(anyString());
        verify(repository, never()).verifyPatient(anyString(), anyString());
        verify(audit).append(any(), eq("EXCHANGE"), eq("WORK_ITEM_LIST"), eq("WORK_ITEM"),
                eq("EXCHANGE"), eq("ALL"), eq("SUCCESS"));
    }

    @Test
    void receiverBindsLocallyVerifiedPatientAndWorkItemBecomesReady() {
        authorized("clinician");
        when(repository.workItem(WORK_ITEM)).thenReturn(
                Optional.of(projectedItem(false)), Optional.of(projectedItem(true)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC")).thenReturn(true);
        when(repository.verifyPatient(WORK_ITEM, LOCAL_PATIENT)).thenReturn(1);

        WorkItemView result = service.verifyPatient(WORK_ITEM, verifyRequest());

        assertEquals(WORK_ITEM, result.id());
        assertEquals(LOCAL_PATIENT, result.localPatientId());
        assertTrue(result.patientVerified());
        assertEquals("ready", result.state());
        assertEquals(FINGERPRINT, result.fingerprint());
        assertEquals("Synthetic CBC question", result.clinicalQuestion(),
                "local verification must not change the projected content");
        verify(repository, times(2)).workItem(WORK_ITEM);
        verify(repository).verifyPatient(WORK_ITEM, LOCAL_PATIENT);
        verify(repository, never()).find(anyString());
        verify(audit).append(any(), eq("EXCHANGE"), eq("WORK_ITEM_VERIFY"), eq("WORK_ITEM"),
                eq("EXCHANGE"), eq(WORK_ITEM), eq("SUCCESS"));
    }

    @Test
    void workItemsAndVerificationRefuseTheWrongRole() {
        // Pre-arm the success path: if the role gate were bypassed, both calls would
        // succeed, so these denials cannot pass vacuously.
        when(repository.workItems()).thenReturn(List.of(projectedItem(false)));
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.of(projectedItem(false)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC")).thenReturn(true);
        when(repository.verifyPatient(WORK_ITEM, LOCAL_PATIENT)).thenReturn(1);
        authorized("reception");

        ApiException listDenial = reject(() -> service.workItems());
        assertEquals(403, listDenial.status());
        assertEquals("FORBIDDEN", listDenial.code());
        ApiException verifyDenial = reject(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));
        assertEquals(403, verifyDenial.status());
        assertEquals("FORBIDDEN", verifyDenial.code());

        verify(repository, never()).workItems();
        verify(repository, never()).workItem(anyString());
        verify(repository, never()).patientExists(anyString());
        verify(repository, never()).localPatientMatches(anyString(), anyString(), anyString());
        verify(repository, never()).verifyPatient(anyString(), anyString());
        verify(audit).append(any(), eq("WORK_ITEM_LIST"), eq("WORK_ITEM_LIST"), eq("ORDER"),
                eq("EXCHANGE"), eq("ALL"), eq("DENIED"));
        verify(audit).append(any(), eq("WORK_ITEM_VERIFY"), eq("WORK_ITEM_VERIFY"), eq("ORDER"),
                eq("EXCHANGE"), eq(WORK_ITEM), eq("DENIED"));
    }

    @Test
    void workItemsAndVerificationRefuseASubjectWithoutActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));

        ApiException listDenial = reject(() -> service.workItems());
        assertEquals(403, listDenial.status());
        assertEquals("FORBIDDEN", listDenial.code());
        ApiException verifyDenial = reject(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));
        assertEquals(403, verifyDenial.status());
        assertEquals("FORBIDDEN", verifyDenial.code());

        verify(repository, never()).workItems();
        verify(repository, never()).workItem(anyString());
        verify(repository, never()).verifyPatient(anyString(), anyString());
        verify(audit).append(any(), eq("WORK_ITEM_LIST"), eq("WORK_ITEM_LIST"), eq("ORDER"),
                eq("EXCHANGE"), eq("ALL"), eq("DENIED"));
        verify(audit).append(any(), eq("WORK_ITEM_VERIFY"), eq("WORK_ITEM_VERIFY"), eq("ORDER"),
                eq("EXCHANGE"), eq(WORK_ITEM), eq("DENIED"));
    }

    @Test
    void senderAndUnrelatedTenantCannotReachTheReceiversWorkItem() {
        authorized("clinician");
        // A mocked repository models what the database returns for a context outside
        // the destination: no projection rows. Real RLS concealment is proven by the
        // real-PostgreSQL OrderExchangeIntegrationTest.
        when(repository.workItems()).thenReturn(List.of());
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.empty());
        // The sender's clinician is authenticated in its own organization.
        TenantContext.set(new TenantContext(ORG, OTHER_ACTOR, "EXCHANGE", "corr-other"));
        when(repository.hasActiveMembership(OTHER_ACTOR)).thenReturn(true);
        when(repository.activeRoles(OTHER_ACTOR)).thenReturn(List.of("clinician"));

        assertTrue(service.workItems().isEmpty(), "another tenant sees no projected work items");
        ApiException missing = reject(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));
        assertEquals(404, missing.status());
        assertEquals("NOT_FOUND", missing.code());
        assertEquals("work item not found", missing.getMessage());

        verify(repository, never()).verifyPatient(anyString(), anyString());
        verify(repository, never()).patientExists(anyString());
        verify(audit, never()).append(any(), eq("WORK_ITEM_VERIFY"), eq("WORK_ITEM_VERIFY"),
                eq("WORK_ITEM"), eq("EXCHANGE"), eq(WORK_ITEM), eq("SUCCESS"));
    }

    @Test
    void verificationRefusesUnmatchedLocalPatient() {
        authorized("clinician");
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.of(projectedItem(false)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);

        // Each side of the OR-arm is refused before any local verification write.
        ApiException wrongMrn = reject(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-2", "CLINIC", "site-verified MRN match")));
        assertEquals(403, wrongMrn.status());
        assertEquals("IDENTITY_MISMATCH", wrongMrn.code());
        // The MRN arm short-circuits before the database identifier match.
        verify(repository, never()).localPatientMatches(anyString(), anyString(), anyString());
        ApiException wrongIssuer = reject(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", "OTHER", "site-verified MRN match")));
        assertEquals("IDENTITY_MISMATCH", wrongIssuer.code());
        verify(repository, never()).localPatientMatches(anyString(), anyString(), anyString());

        // A matching projected MRN still needs a real receiver-side identifier match.
        when(repository.localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC")).thenReturn(false);
        ApiException unmatched = reject(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));
        assertEquals(403, unmatched.status());
        assertEquals("IDENTITY_MISMATCH", unmatched.code());
        assertEquals("the local patient does not match the referred patient identity",
                unmatched.getMessage());
        verify(repository).localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC");

        verify(repository, never()).verifyPatient(anyString(), anyString());
        verify(audit, never()).append(any(), eq("WORK_ITEM_VERIFY"), eq("WORK_ITEM_VERIFY"),
                eq("WORK_ITEM"), eq("EXCHANGE"), eq(WORK_ITEM), eq("SUCCESS"));
    }

    @Test
    void verificationRefusesBlankIdentityFieldsAndInvalidIds() {
        authorized("clinician");

        invalid(() -> service.verifyPatient("not-a-uuid", verifyRequest()));
        invalid(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest("not-a-uuid", "MRN-1", "CLINIC", "attested")));
        invalid(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, " ", "CLINIC", "attested")));
        invalid(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", " ", "attested")));
        invalid(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", "CLINIC", " ")));

        verify(repository, never()).workItem(anyString());
        verify(repository, never()).verifyPatient(anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void repeatVerificationIsRejected() {
        authorized("clinician");
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.of(projectedItem(false)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC")).thenReturn(true);
        // The one-way database update returns zero rows on a repeated verification.
        when(repository.verifyPatient(WORK_ITEM, LOCAL_PATIENT)).thenReturn(0);

        ApiException repeat = reject(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));
        assertEquals(409, repeat.status());
        assertEquals("ALREADY_VERIFIED", repeat.code());
        assertEquals("the work item is already verified", repeat.getMessage());
        // The post-update re-read is never reached on the conflict path.
        verify(repository, times(1)).workItem(WORK_ITEM);
        verify(repository).verifyPatient(WORK_ITEM, LOCAL_PATIENT);
        verify(audit, never()).append(any(), eq("WORK_ITEM_VERIFY"), eq("WORK_ITEM_VERIFY"),
                eq("WORK_ITEM"), eq("EXCHANGE"), eq(WORK_ITEM), eq("SUCCESS"));
    }
}
