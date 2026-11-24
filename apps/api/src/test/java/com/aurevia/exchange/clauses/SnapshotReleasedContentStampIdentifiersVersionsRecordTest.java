package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.order.AuthorizationView;
import com.aurevia.exchange.order.OrderRepository;
import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.order.OrderService;
import com.aurevia.exchange.order.OrderView;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-01 clause 3 (unit acceptance): releasing an order must snapshot the exact
 * released content, stamp opaque identifiers and version one, and record a bounded
 * disclosure; a refused release must record nothing.
 *
 * <p>The content and fingerprint expectations below use a literal U+001F separator
 * (not the production constant) and an offline-precomputed UUID v3 fingerprint, so a
 * broken separator, projection or digest cannot silently pass. Neither is copied from
 * the value under test. The repository and audit are mocked at the same boundary as the neighbouring
 * {@code exchange} unit tests; the HTTP acceptance lives in
 * {@code com.aurevia.it.exchangeorder.OrderExchangeIntegrationTest}. All
 * fixtures are synthetic.</p>
 */
class SnapshotReleasedContentStampIdentifiersVersionsRecordTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String ACTOR = "clinician-a";
    private static final String ORDER_CODE = "CBC";
    private static final String ENCOUNTER = "ENC-1";
    private static final String PRIORITY = "routine";
    private static final String QUESTION = "Synthetic CBC question";
    private static final String PURPOSE = "lab review";
    private static final String RESPONSIBLE = "dr-a";
    private static final String WORK_QUEUE = "lab-queue";
    private static final String MRN = "MRN-1";
    private static final String ISSUER = "CLINIC";
    private static final int DURATION = 30;
    private static final int MAX_DURATION = 60;
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    /** The literal unit separator, deliberately not the production constant under test. */
    private static final String SEPARATOR = "\u001f";
    /** UUID v3 (MD5) fingerprint precomputed offline from the literal content below. */
    private static final String EXPECTED_FINGERPRINT = "2c7b7a47-6c79-3629-9d1d-a7351ebf66b8";

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;

    /** Build the service with the same mocked transaction boundary as neighbouring tests. */
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
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-clause-3"));
        authorized("clinician");
    }

    /** Clear the request-scoped tenant after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    private static AuthorizationView grant(int maxDuration, Instant expiresAt) {
        return new AuthorizationView(GRANT, ORG, ORDER_CODE, DEST, PURPOSE, maxDuration,
                NOW.minusSeconds(60), expiresAt, true);
    }

    private static OrderRequest request(int duration) {
        return new OrderRequest(PATIENT, ENCOUNTER, ORDER_CODE, PRIORITY, QUESTION, DEST, PURPOSE,
                GRANT, RESPONSIBLE, WORK_QUEUE, duration);
    }

    private void anApprovedGrantAndPatient() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant(MAX_DURATION, NOW.plusSeconds(3600))));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity(MRN, ISSUER)));
    }

    /**
     * Independent canonical projection: the create() field order joined by a
     * literal U+001F, never the production separator constant. It also spells out
     * "30" rather than reading the request again, so a change to the projection,
     * separator or version prefix cannot silently pass.
     */
    private static String expectedContent() {
        return String.join(SEPARATOR, "v1", ORDER_CODE, PATIENT, ENCOUNTER, PRIORITY,
                QUESTION, DEST, PURPOSE, RESPONSIBLE, WORK_QUEUE, "30");
    }

    /** Verify that none of the four ledger writes ran and no success was recorded. */
    private void assertNoRecordedRelease() {
        verify(repository, never()).insertOrder(any());
        verify(repository, never()).appendSnapshot(anyString(), anyString(), anyInt(), anyString(), anyString());
        verify(repository, never()).appendDisclosure(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), anyString());
        verify(repository, never()).insertWorkItem(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void releaseSnapshotsTheExactContentWithVersionOneAndStampedIdentifiers() {
        anApprovedGrantAndPatient();

        OrderView released = service.create(request(DURATION));

        assertEquals(1, released.version(), "a release starts at version one");
        assertEquals(NOW, released.createdAt());
        assertEquals(PATIENT, released.patientId());
        assertEquals(GRANT, released.authorizationId());
        assertEquals(ACTOR, released.sourceActor(), "the actor is derived from the tenant, not the payload");

        Set<String> stamped = Set.of(released.id(), released.snapshotId(), released.disclosureId(),
                released.workItemId());
        assertEquals(4, stamped.size(), "the order, snapshot, disclosure and work item ids are distinct");
        stamped.forEach(id -> {
            assertEquals(id, UUID.fromString(id).toString(), "each stamp is a canonical opaque UUID");
            assertFalse(Set.of(PATIENT, DEST, GRANT).contains(id), "a stamp is not a client-supplied identifier");
        });

        String content = expectedContent();
        assertEquals(EXPECTED_FINGERPRINT, released.snapshotFingerprint(),
                "the projected fingerprint binds the exact released fields");

        verify(repository).insertOrder(released);
        verify(repository).appendSnapshot(released.snapshotId(), released.id(), 1, content, EXPECTED_FINGERPRINT);
        verify(repository).appendDisclosure(released.disclosureId(), released.id(), DEST, PURPOSE,
                DURATION, NOW.plusSeconds(1800), EXPECTED_FINGERPRINT);
        verify(repository).insertWorkItem(released.workItemId(), DEST, GRANT, released.id(), PATIENT,
                MRN, ISSUER, PRIORITY, QUESTION, WORK_QUEUE, EXPECTED_FINGERPRINT);
        verify(audit).append(TenantContext.current(), "EXCHANGE", "ORDER_DISCLOSURE", "ORDER",
                "EXCHANGE", released.id(), "SUCCESS");
    }

    @Test
    void fingerprintIsDeterministicForTheSameReleasedContentAndChangesWhenContentChanges() {
        anApprovedGrantAndPatient();

        OrderView first = service.create(request(DURATION));
        OrderView repeat = service.create(request(DURATION));
        OrderView changed = service.create(new OrderRequest(PATIENT, ENCOUNTER, ORDER_CODE, PRIORITY,
                "A different synthetic question", DEST, PURPOSE, GRANT, RESPONSIBLE, WORK_QUEUE, DURATION));

        assertEquals(EXPECTED_FINGERPRINT, first.snapshotFingerprint());
        assertEquals(first.snapshotFingerprint(), repeat.snapshotFingerprint(),
                "the same released content has the same fingerprint");
        assertNotEquals(first.snapshotFingerprint(), changed.snapshotFingerprint(),
                "a changed question changes the fingerprint");
        assertNotEquals(first.id(), repeat.id(), "each release still gets a fresh order identifier");
    }

    @Test
    void refusesReleaseForAnActorWithoutTheExchangeRole() {
        anApprovedGrantAndPatient();

        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException noMembership = assertThrows(ApiException.class, () -> service.create(request(DURATION)));
        assertEquals(403, noMembership.status());
        assertEquals("FORBIDDEN", noMembership.code());

        authorized("reception");
        ApiException wrongRole = assertThrows(ApiException.class, () -> service.create(request(DURATION)));
        assertEquals(403, wrongRole.status());
        assertEquals("FORBIDDEN", wrongRole.code());

        verify(repository, never()).authorization(GRANT);
        assertNoRecordedRelease();
        verify(audit, times(2)).append(TenantContext.current(), "ORDER_DISCLOSURE", "ORDER_DISCLOSURE",
                "ORDER", "EXCHANGE", GRANT, "DENIED");
    }

    @Test
    void recordsNoDisclosureWhenTheAuthorizationCannotCoverTheRelease() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant(MAX_DURATION, null)));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity(MRN, ISSUER)));

        ApiException overDuration = assertThrows(ApiException.class, () -> service.create(request(61)));

        assertEquals(403, overDuration.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", overDuration.code());
        assertNoRecordedRelease();
        verify(audit).append(TenantContext.current(), "ORDER_DISCLOSURE", "ORDER_DISCLOSURE",
                "ORDER", "EXCHANGE", GRANT, "DENIED");
    }

    @Test
    void recordsNoDisclosureWhenTheGrantExpiresBeforeTheDisclosureWould() {
        when(repository.authorization(GRANT)).thenReturn(
                Optional.of(grant(MAX_DURATION, NOW.plusSeconds(300))));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity(MRN, ISSUER)));

        ApiException outliving = assertThrows(ApiException.class, () -> service.create(request(DURATION)));

        assertEquals(403, outliving.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", outliving.code());
        assertNoRecordedRelease();
        verify(audit).append(TenantContext.current(), "ORDER_DISCLOSURE", "ORDER_DISCLOSURE",
                "ORDER", "EXCHANGE", GRANT, "DENIED");
    }

    @Test
    void rejectsMissingInputBeforeAnySnapshotIsTaken() {
        ApiException invalid = assertThrows(ApiException.class,
                () -> service.create(new OrderRequest("not-a-uuid", ENCOUNTER, ORDER_CODE, PRIORITY,
                        QUESTION, DEST, PURPOSE, GRANT, RESPONSIBLE, WORK_QUEUE, DURATION)));

        assertEquals(422, invalid.status());
        assertEquals("INVALID_INPUT", invalid.code());
        assertNoRecordedRelease();
        verifyNoInteractions(audit);
    }
}
