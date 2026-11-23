package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.order.AuthorizationView;
import com.aurevia.smartclinic.exchange.order.OrderInputs;
import com.aurevia.smartclinic.exchange.order.OrderRepository;
import com.aurevia.smartclinic.exchange.order.OrderRequest;
import com.aurevia.smartclinic.exchange.order.OrderService;
import com.aurevia.smartclinic.exchange.order.OrderView;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * XCH-01 clause 1 proof: a released order carries the local patient and encounter,
 * order code, priority, clinical question, destination, source actor and responsible
 * clinician/work queue.
 *
 * <p>The entry point is {@code OrderService.create}, the same domain command the thin
 * {@code OrderController} delegates to on {@code POST /api/v1/orders}. The source actor is
 * deliberately not a request field: the service must derive it from the active
 * {@code TenantContext} subject. The released content snapshot and the destination work-item
 * projection must carry the clinical fields, and every refusal must happen before any of the
 * four exchange writes. All fixtures are synthetic; no PHI or credentials are used.</p>
 */
class OrderIncludesLocalPatientEncounterOrderCodeTest {

    private static final String ORG = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DEST = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String GRANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String ACTOR = "clause-one-actor";
    private static final String RESPONSIBLE = "dr-clause-one";
    private static final String WORK_QUEUE = "clause-queue";
    private static final String ORDER_CODE = "CLS1";
    private static final String ENCOUNTER = "ENC-CLAUSE-1";
    private static final String QUESTION = "Synthetic clause-one question";
    private static final String MRN = "MRN-CLAUSE-1";
    private static final String ISSUER = "CLAUSE-ISSUER";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;

    /** Build the service with mocked boundaries and a transaction that runs its callback inline. */
    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new OrderService(repository, audit, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-clause-one"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Model an active member holding exactly one role in the active organization. */
    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    /** A synthetic authorization whose order code, destination, purpose and duration admit the request. */
    private static AuthorizationView grant() {
        return new AuthorizationView(GRANT, ORG, ORDER_CODE, DEST, "lab review", 60,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), true);
    }

    /** A synthetic release command carrying all nine clause-one fields. */
    private static OrderRequest request() {
        return new OrderRequest(PATIENT, ENCOUNTER, ORDER_CODE, "routine", QUESTION, DEST,
                "lab review", GRANT, RESPONSIBLE, WORK_QUEUE, 30);
    }

    /** Assert that a refusal reached none of the order, snapshot, disclosure or work-item writes. */
    private void verifyNoOrderWrites() {
        verify(repository, never()).insertOrder(any());
        verify(repository, never()).appendSnapshot(anyString(), anyString(), anyInt(),
                anyString(), anyString());
        verify(repository, never()).appendDisclosure(anyString(), anyString(), anyString(),
                anyString(), anyInt(), any(), anyString());
        verify(repository, never()).insertWorkItem(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    /** Positive proof: every clause field is stamped, snapshotted and projected. */
    @Test
    void releasedOrderCarriesEveryClauseOneField() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT))
                .thenReturn(Optional.of(new OrderRepository.PatientIdentity(MRN, ISSUER)));

        OrderView released = service.create(request());

        assertEquals(1, released.version());
        assertEquals(PATIENT, released.patientId());
        assertEquals(ENCOUNTER, released.encounterRef());
        assertEquals(ORDER_CODE, released.orderCode());
        assertEquals("routine", released.priority());
        assertEquals(QUESTION, released.clinicalQuestion());
        assertEquals(DEST, released.destinationOrg());
        // OrderRequest has no sourceActor field; the responsible clinician is deliberately
        // different, so this assertion can only pass if the actor comes from TenantContext.
        assertEquals(ACTOR, released.sourceActor());
        assertEquals(RESPONSIBLE, released.responsibleClinician());
        assertEquals(WORK_QUEUE, released.workQueue());
        assertEquals(GRANT, released.authorizationId());
        assertEquals(NOW, released.createdAt());
        assertNotNull(released.snapshotId());
        assertNotNull(released.disclosureId());
        assertNotNull(released.workItemId());
        assertNotNull(released.snapshotFingerprint());

        verify(repository).insertOrder(released);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(repository).appendSnapshot(eq(released.snapshotId()), eq(released.id()), eq(1),
                content.capture(), eq(released.snapshotFingerprint()));
        List<String> stamped = List.of(content.getValue().split(OrderInputs.SEPARATOR, -1));
        assertTrue(stamped.contains(PATIENT));
        assertTrue(stamped.contains(ENCOUNTER));
        assertTrue(stamped.contains(ORDER_CODE));
        assertTrue(stamped.contains("routine"));
        assertTrue(stamped.contains(QUESTION));
        assertTrue(stamped.contains(DEST));
        assertTrue(stamped.contains(RESPONSIBLE));
        assertTrue(stamped.contains(WORK_QUEUE));
        assertFalse(stamped.contains(ISSUER), "no issuer is a released order field");

        verify(repository).appendDisclosure(released.disclosureId(), released.id(), DEST,
                "lab review", 30, NOW.plusSeconds(1800), released.snapshotFingerprint());
        verify(repository).insertWorkItem(released.workItemId(), DEST, GRANT, released.id(),
                PATIENT, MRN, ISSUER, "routine", QUESTION, WORK_QUEUE, released.snapshotFingerprint());
        verify(audit).append(any(), eq("EXCHANGE"), eq("ORDER_DISCLOSURE"), eq("ORDER"),
                eq("EXCHANGE"), eq(released.id()), eq("SUCCESS"));
    }

    /** Negative proof: an unapproved priority is refused at the trust boundary before any write. */
    @Test
    void priorityOutsideApprovedSetIsRefusedBeforeAnyWrite() {
        ApiException failure = assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, ENCOUNTER, ORDER_CODE, "soon", QUESTION, DEST,
                        "lab review", GRANT, RESPONSIBLE, WORK_QUEUE, 30)));

        assertEquals(422, failure.status());
        assertEquals("INVALID_PRIORITY", failure.code());
        verifyNoInteractions(repository);
        verifyNoInteractions(audit);
    }

    /** Negative proof: the ordering role is required and a refusal is audited as DENIED. */
    @Test
    void orderingRoleIsRequiredAndDenialIsAudited() {
        authorized("reception");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT))
                .thenReturn(Optional.of(new OrderRepository.PatientIdentity(MRN, ISSUER)));

        ApiException failure = assertThrows(ApiException.class, () -> service.create(request()));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(audit).append(any(), eq("ORDER_DISCLOSURE"), eq("ORDER_DISCLOSURE"), eq("ORDER"),
                eq("EXCHANGE"), eq(GRANT), eq("DENIED"));
        verifyNoOrderWrites();
    }

    /** Negative proof: a patient not visible in the active tenant conceals itself with 404. */
    @Test
    void unknownLocalPatientIsRefused() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> service.create(request()));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verifyNoOrderWrites();
    }
}
