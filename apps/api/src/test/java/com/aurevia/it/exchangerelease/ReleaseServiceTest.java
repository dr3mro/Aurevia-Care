package com.aurevia.it.exchangerelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.release.AuthorizeRelease;
import com.aurevia.exchange.release.PatientRelease;
import com.aurevia.exchange.release.ReleaseActionPolicy;
import com.aurevia.exchange.release.ReleaseKind;
import com.aurevia.exchange.release.ReleaseRepository;
import com.aurevia.exchange.release.ReleaseService;
import com.aurevia.exchange.release.ReleaseState;
import com.aurevia.tenant.TenantContext;

/** S-G2-06 fast unit slice: release authorization, timed/hold visibility and withdrawal (XCH-06). */
class ReleaseServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String REVISION = "LAB-REV-0001";
    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private ReleaseRepository repository;
    private TransactionTemplate transactions;
    private ReleaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReleaseRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new ReleaseService(repository, new ReleaseActionPolicy(), transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "RELEASE_ADMIN", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles));
    }

    private AuthorizeRelease immediate() {
        return new AuthorizeRelease(REVISION, 1, PATIENT, 2, "IMMEDIATE", null, null, null);
    }

    private PatientRelease release(ReleaseState state, String effectiveFrom, int version) {
        return new PatientRelease("rel-1", ORG, PATIENT, REVISION, 1, ORG, 2,
                state == ReleaseState.HELD ? ReleaseKind.HOLD : ReleaseKind.IMMEDIATE,
                state, Instant.parse(effectiveFrom), null, null, null, null, null,
                version, "author-1", NOW);
    }

    @Test
    void rejectsBlankRevisionId() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(" ", 1, PATIENT, 2, "IMMEDIATE", null, null, null)))
                .status());
    }

    @Test
    void rejectsBadRevisionVersion() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 0, PATIENT, 2, "IMMEDIATE", null, null, null)))
                .status());
    }

    @Test
    void rejectsBadPatientId() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, "not-a-uuid", 2, "IMMEDIATE", null, null, null)))
                .status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, null, 2, "IMMEDIATE", null, null, null)))
                .status());
    }

    @Test
    void rejectsBadPolicyVersion() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 0, "IMMEDIATE", null, null, null)))
                .status());
    }

    @Test
    void rejectsBlankKind() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "  ", null, null, null)))
                .status());
    }

    @Test
    void rejectsHoldWithoutReason() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "HOLD", null, " ", null)))
                .status());
    }

    @Test
    void rejectsHoldWithoutExpiry() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "HOLD", null, "review", " ")))
                .status());
    }

    @Test
    void rejectsHoldWithExpiryInPast() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "HOLD", null,
                        "review", NOW.minusSeconds(1).toString()))).status());
    }

    @Test
    void rejectsUnparseableHoldExpiry() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "HOLD", null,
                        "review", "not-a-date"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "HOLD", null,
                        null, null))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "HOLD", null,
                        "review", null))).status());
    }

    @Test
    void rejectsTimedWithoutEffectiveFrom() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "TIMED", null, null, null)))
                .status());
    }

    @Test
    void rejectsUnparseableEffectiveFrom() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "TIMED",
                        "not-a-date", null, null))).status());
    }

    @Test
    void authorizeDeniesInactiveMembershipAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.authorize(immediate())).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_AUTHORIZE", REVISION, "DENIED");
        verify(repository, never()).insertRelease(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyInt(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void authorizeDeniesMissingPermissionAndAudits() {
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class, () -> service.authorize(immediate())).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_AUTHORIZE", REVISION, "DENIED");
    }

    @Test
    void authorizeRejectsDuplicateActiveRevision() {
        authorized("clinician");
        when(repository.findAnyByPatientAndRevision(PATIENT, REVISION))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.authorize(immediate())).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_AUTHORIZE", REVISION, "CONFLICT");
    }

    @Test
    void authorizeRejectsConcurrentDuplicateWithoutFurtherSqlOnAbortedTransaction() {
        authorized("clinician");
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("race"));
        // A unique violation aborts the PostgreSQL transaction; any subsequent statement fails.
        doThrow(new IllegalStateException("current transaction is aborted"))
                .when(repository).appendAudit(any(), any(), any(), any());
        assertEquals(409, assertThrows(ApiException.class, () -> service.authorize(immediate())).status());
        verify(repository, never()).appendAudit(any(), any(), any(), any());
    }

    @Test
    void authorizeImmediateIsVisibleAndNotifies() {
        authorized("clinician");
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rel-1");
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        PatientRelease result = service.authorize(immediate());
        assertTrue(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
        assertEquals(ReleaseState.AUTHORIZED, result.state());
        verify(repository).insertEvent(TenantContext.current(), "rel-1", "RELEASE_AUTHORIZE",
                null, "AUTHORIZED", null);
        verify(repository).insertNotification(TenantContext.current(), "rel-1",
                "RELEASE_AUTHORIZED", "linked-patient");
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_AUTHORIZE", REVISION, "SUCCESS");
    }

    @Test
    void authorizeTimedFutureIsNotVisibleYet() {
        authorized("clinician");
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rel-1");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.plusSeconds(60).toString(), 1)));
        PatientRelease result = service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "TIMED",
                NOW.plusSeconds(60).toString(), null, null));
        assertFalse(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void authorizeHoldIsNotVisibleUntilReviewed() {
        authorized("clinician");
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rel-1");
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(release(ReleaseState.HELD, NOW.toString(), 1)));
        PatientRelease result = service.authorize(new AuthorizeRelease(REVISION, 1, PATIENT, 2, "HOLD",
                null, "needs review", NOW.plusSeconds(3600).toString()));
        assertEquals(ReleaseState.HELD, result.state());
        assertFalse(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void liftHoldRequiresReviewerAndAudits() {
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class, () -> service.liftHold("rel-1", 1, "ok")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_LIFT_HOLD", "rel-1", "DENIED");
    }

    @Test
    void liftHoldMakesReleaseVisible() {
        authorized("clinician");
        when(repository.findRelease("rel-1")).thenReturn(
                Optional.of(release(ReleaseState.HELD, NOW.toString(), 1)),
                Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 2)));
        when(repository.liftHold("rel-1", 1)).thenReturn(1);
        PatientRelease result = service.liftHold("rel-1", 1, "reviewed");
        assertTrue(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
        verify(repository).insertNotification(TenantContext.current(), "rel-1",
                "HOLD_LIFTED", "linked-patient");
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_LIFT_HOLD", "rel-1", "SUCCESS");
    }

    @Test
    void liftHoldRejectsWrongState() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.liftHold("rel-1", 1, "x")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_LIFT_HOLD", "rel-1", "CONFLICT");
    }

    @Test
    void liftHoldConcealsUnknownRelease() {
        authorized("clinician");
        when(repository.findRelease("rel-1")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.liftHold("rel-1", 1, "x")).status());
    }

    @Test
    void liftHoldRejectsConcurrentUpdate() {
        authorized("clinician");
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(release(ReleaseState.HELD, NOW.toString(), 1)));
        when(repository.liftHold("rel-1", 1)).thenReturn(0);
        assertEquals(409, assertThrows(ApiException.class, () -> service.liftHold("rel-1", 1, "x")).status());
    }

    @Test
    void withdrawRejectsBlankReason() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.withdraw("rel-1", 1, " ")).status());
    }

    @Test
    void withdrawStopsAccessAndNotifiesPriorRecipients() {
        authorized("clinician");
        when(repository.findRelease("rel-1")).thenReturn(
                Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)),
                Optional.of(release(ReleaseState.WITHDRAWN, NOW.toString(), 2)));
        when(repository.withdraw("rel-1", 1)).thenReturn(1);
        PatientRelease result = service.withdraw("rel-1", 1, "retracted");
        assertFalse(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
        verify(repository).insertEvent(TenantContext.current(), "rel-1", "RELEASE_WITHDRAW",
                "AUTHORIZED", "WITHDRAWN", "retracted");
        verify(repository).insertNotification(TenantContext.current(), "rel-1",
                "RELEASE_WITHDRAWN", "prior-recipients");
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_WITHDRAW", "rel-1", "SUCCESS");
    }

    @Test
    void withdrawRejectsStaleVersion() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 3)));
        assertEquals(412, assertThrows(ApiException.class, () -> service.withdraw("rel-1", 1, "r")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_WITHDRAW", "rel-1", "STALE");
    }

    @Test
    void withdrawRejectsTerminalState() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.WITHDRAWN, NOW.toString(), 2)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.withdraw("rel-1", 2, "r")).status());
    }

    @Test
    void withdrawRejectsConcurrentUpdate() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        when(repository.withdraw("rel-1", 1)).thenReturn(0);
        assertEquals(409, assertThrows(ApiException.class, () -> service.withdraw("rel-1", 1, "r")).status());
    }

    @Test
    void correctSupersedesAndCreatesNewRevision() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        when(repository.correct("rel-1", 1)).thenReturn(1);
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), eq("rel-1")))
                .thenReturn("rel-2");
        when(repository.findRelease("rel-2"))
                .thenReturn(Optional.of(new PatientRelease("rel-2", ORG, PATIENT, "LAB-REV-0002", 2,
                        ORG, 2, ReleaseKind.IMMEDIATE, ReleaseState.AUTHORIZED, NOW,
                        null, null, null, null, "rel-1", 1, "author-1", NOW)));
        PatientRelease result = service.correct("rel-1", 1, "LAB-REV-0002", 2, "corrected result");
        assertEquals("rel-1", result.supersedesId());
        assertEquals(ReleaseState.AUTHORIZED, result.state());
        verify(repository).insertNotification(TenantContext.current(), "rel-1",
                "RELEASE_CORRECTED", "prior-recipients");
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_CORRECT", "rel-1", "SUCCESS");
    }

    @Test
    void correctOfReviewedHoldPublishesAnImmediatelyVisibleReplacement() {
        // A hold that was reviewed (lifted) keeps kind=HOLD in the database but is AUTHORIZED.
        // Its replacement must be a fresh IMMEDIATE authorization: reusing HOLD with null
        // hold reason/expiry violates the V24 CHECK and leaves the patient with no access.
        authorized("clinician");
        PatientRelease reviewedHold = new PatientRelease("rel-1", ORG, PATIENT, REVISION, 1, ORG, 2,
                ReleaseKind.HOLD, ReleaseState.AUTHORIZED, NOW, "needs review",
                NOW.plusSeconds(3600), "reviewer-1", NOW, null, 1, "author-1", NOW);
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(reviewedHold));
        when(repository.correct("rel-1", 1)).thenReturn(1);
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), eq("rel-1")))
                .thenReturn("rel-2");
        when(repository.findRelease("rel-2")).thenReturn(Optional.of(new PatientRelease("rel-2", ORG, PATIENT,
                "LAB-REV-0002", 2, ORG, 2, ReleaseKind.IMMEDIATE, ReleaseState.AUTHORIZED, NOW,
                null, null, null, null, "rel-1", 1, "author-1", NOW)));

        PatientRelease result = service.correct("rel-1", 1, "LAB-REV-0002", 2, "corrected result");

        assertEquals("rel-1", result.supersedesId());
        assertTrue(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
        ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
        verify(repository).insertRelease(eq(ORG), eq(PATIENT), eq("LAB-REV-0002"), eq(2), eq(ORG), eq(2),
                kind.capture(), eq("AUTHORIZED"), any(), isNull(), isNull(), eq("rel-1"));
        assertEquals("IMMEDIATE", kind.getValue());
    }

    @Test
    void correctOfReviewedHoldWithFutureEffectiveTimeKeepsIt() {
        // A HOLD may carry a future effective instant. Lifting it does not change that instant;
        // correcting the lifted hold must not silently open access before the configured time.
        authorized("clinician");
        Instant future = NOW.plusSeconds(3600);
        PatientRelease reviewedHold = new PatientRelease("rel-1", ORG, PATIENT, REVISION, 1, ORG, 2,
                ReleaseKind.HOLD, ReleaseState.AUTHORIZED, future, "needs review",
                future.plusSeconds(3600), "reviewer-1", NOW, null, 1, "author-1", NOW);
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(reviewedHold));
        when(repository.findAnyByPatientAndRevision(PATIENT, "LAB-REV-0002")).thenReturn(Optional.empty());
        when(repository.correct("rel-1", 1)).thenReturn(1);
        when(repository.insertRelease(eq(ORG), eq(PATIENT), eq("LAB-REV-0002"), eq(2), eq(ORG), eq(2),
                eq("IMMEDIATE"), eq("AUTHORIZED"), any(), isNull(), isNull(), eq("rel-1")))
                .thenReturn("rel-2");
        when(repository.findRelease("rel-2")).thenReturn(Optional.of(new PatientRelease("rel-2", ORG, PATIENT,
                "LAB-REV-0002", 2, ORG, 2, ReleaseKind.IMMEDIATE, ReleaseState.AUTHORIZED, future,
                null, null, null, null, "rel-1", 1, "author-1", NOW)));

        PatientRelease result = service.correct("rel-1", 1, "LAB-REV-0002", 2, "corrected result");

        ArgumentCaptor<Instant> effective = ArgumentCaptor.forClass(Instant.class);
        verify(repository).insertRelease(eq(ORG), eq(PATIENT), eq("LAB-REV-0002"), eq(2), eq(ORG), eq(2),
                eq("IMMEDIATE"), eq("AUTHORIZED"), effective.capture(), isNull(), isNull(), eq("rel-1"));
        assertEquals(future, effective.getValue());
        assertFalse(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void correctPreservesTimedReleasePolicy() {
        authorized("clinician");
        Instant future = NOW.plusSeconds(3600);
        PatientRelease timed = new PatientRelease("rel-1", ORG, PATIENT, REVISION, 1, ORG, 2,
                ReleaseKind.TIMED, ReleaseState.AUTHORIZED, future, null, null, null, null, null,
                1, "author-1", NOW);
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(timed));
        when(repository.correct("rel-1", 1)).thenReturn(1);
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), eq("rel-1")))
                .thenReturn("rel-2");
        when(repository.findRelease("rel-2")).thenReturn(Optional.of(new PatientRelease("rel-2", ORG, PATIENT,
                "LAB-REV-0002", 2, ORG, 2, ReleaseKind.TIMED, ReleaseState.AUTHORIZED, future,
                null, null, null, null, "rel-1", 1, "author-1", NOW)));

        PatientRelease result = service.correct("rel-1", 1, "LAB-REV-0002", 2, "corrected result");

        assertEquals("rel-1", result.supersedesId());
        assertFalse(result.visibleNow(Clock.fixed(NOW, ZoneOffset.UTC)));
        ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> effective = ArgumentCaptor.forClass(Instant.class);
        verify(repository).insertRelease(eq(ORG), eq(PATIENT), eq("LAB-REV-0002"), eq(2), eq(ORG), eq(2),
                kind.capture(), eq("AUTHORIZED"), effective.capture(), isNull(), isNull(), eq("rel-1"));
        assertEquals("TIMED", kind.getValue());
        assertEquals(future, effective.getValue());
    }

    @Test
    void correctRejectsReplacingARevisionWithItself() {
        // A correction must point at a different immutable revision; re-pointing at the same
        // revision would re-authorize the very row just marked CORRECTED.
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, REVISION, 2, "why")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_CORRECT", "rel-1", "CONFLICT");
        verify(repository, never()).correct("rel-1", 1);
    }

    @Test
    void correctRejectsBlankCorrectedRevision() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, " ", 2, "why")).status());
    }

    @Test
    void correctRejectsAlreadyReleasedReplacementRevisionAndAuditsConflict() {
        // A correction onto a revision that already has a live authorization must fail closed
        // before any CAS update, with the same audited CONFLICT outcome as authorize().
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        when(repository.findAnyByPatientAndRevision(PATIENT, "LAB-REV-0002"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 2, "why")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_CORRECT", "rel-1", "CONFLICT");
        verify(repository, never()).correct("rel-1", 1);
        verify(repository, never()).insertRelease(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyInt(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void getConcealsUnknownRelease() {
        authorized("clinician");
        when(repository.findRelease("missing")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get("missing")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_READ", "missing", "NOT_FOUND");
    }

    @Test
    void getDeniesUnauthorizedReader() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get("rel-1")).status());
    }

    @Test
    void getAuditsSuccess() {
        authorized("clinician");
        PatientRelease found = release(ReleaseState.AUTHORIZED, NOW.toString(), 1);
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(found));
        assertEquals(found, service.get("rel-1"));
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_READ", "rel-1", "SUCCESS");
    }

    @Test
    void historyConcealsUnknownRelease() {
        authorized("clinician");
        when(repository.findRelease("missing")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.history("missing")).status());
    }

    @Test
    void historyAuditsSuccess() {
        authorized("clinician");
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        when(repository.findEvents("rel-1")).thenReturn(List.of());
        assertTrue(service.history("rel-1").isEmpty());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_HISTORY", "rel-1", "SUCCESS");
    }

    @Test
    void visibleRevisionsOnlyReturnsCurrentlyVisible() {
        authorized("clinician");
        PatientRelease visible = release(ReleaseState.AUTHORIZED, NOW.toString(), 1);
        PatientRelease future = release(ReleaseState.AUTHORIZED, NOW.plusSeconds(60).toString(), 1);
        when(repository.findAuthorizedByPatient(PATIENT)).thenReturn(List.of(visible, future,
                release(ReleaseState.WITHDRAWN, NOW.toString(), 2)));
        assertEquals(List.of(REVISION), service.visibleRevisions(PATIENT));
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_ACCESS_QUERY", PATIENT, "SUCCESS");
    }

    @Test
    void visibleRevisionsDeniesUnauthorizedReader() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of());
        assertEquals(403, assertThrows(ApiException.class, () -> service.visibleRevisions(PATIENT)).status());
    }

    @Test
    void visibleRevisionsDeniesReceptionAndAuditorEnumeration() {
        // AUD-20261119-01: the patient-scoped revision query is not a general
        // staff enumeration surface. Reception and privacy audit do not need it.
        for (String role : List.of("reception", "privacy_auditor")) {
            authorized(role);
            assertEquals(403, assertThrows(ApiException.class, () -> service.visibleRevisions(PATIENT)).status());
        }
        verify(repository, never()).findAuthorizedByPatient(anyString());
        verify(repository, times(2)).appendAudit(TenantContext.current(), "RELEASE_ACCESS_QUERY", PATIENT, "DENIED");
    }

    @Test
    void verifiedPatientSeesOnlyTheirOwnCurrentlyVisibleRevisions() {
        // AUD-20261119-01: PAT-02 self-access. A verified link to this exact chart
        // authorizes the read without any staff membership or staff role.
        when(repository.hasVerifiedChartLink(ACTOR, PATIENT)).thenReturn(true);
        when(repository.findAuthorizedByPatient(PATIENT)).thenReturn(List.of(
                release(ReleaseState.AUTHORIZED, NOW.toString(), 1),
                release(ReleaseState.AUTHORIZED, NOW.plusSeconds(60).toString(), 1),
                release(ReleaseState.WITHDRAWN, NOW.toString(), 2)));
        assertEquals(List.of(REVISION), service.visibleRevisions(PATIENT));
        verify(repository, never()).hasActiveMembership(anyString());
        verify(repository, never()).activeRoles(anyString());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_ACCESS_QUERY", PATIENT, "SUCCESS");
    }

    @Test
    void patientLinkedToAnotherChartCannotEnumerateThisPatient() {
        // The link check is bound to the path patient id, not just to "some chart".
        when(repository.hasVerifiedChartLink(ACTOR, PATIENT)).thenReturn(false);
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.visibleRevisions(PATIENT)).status());
        verify(repository, never()).findAuthorizedByPatient(anyString());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_ACCESS_QUERY", PATIENT, "DENIED");
    }

    @Test
    void patientScopedReadRequiresTenantContext() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, () -> service.visibleRevisions(PATIENT)).status());
        verify(repository, never()).appendAudit(any(), any(), any(), any());
    }

    @Test
    void rejectsNullCommandAndNullRevision() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.authorize(new AuthorizeRelease(null, 1, PATIENT, 2, "IMMEDIATE", null, null, null)))
                .status());
    }

    @Test
    void blankEffectiveFromUsesInjectedClockForImmediate() {
        authorized("clinician");
        when(repository.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any())).thenReturn("rel-1");
        when(repository.findRelease("rel-1")).thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        assertEquals(ReleaseState.AUTHORIZED, service.authorize(new AuthorizeRelease(
                REVISION, 1, PATIENT, 2, "IMMEDIATE", "  ", null, null)).state());
    }

    @Test
    void blankTimedEffectiveFromIsRejected() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(new AuthorizeRelease(
                REVISION, 1, PATIENT, 2, "TIMED", "  ", null, null))).status());
    }

    @Test
    void correctRejectsNullAndInvalidInputs() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, null, 2, "why")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 0, "why")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 2, null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.get(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.get("!!")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.history(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.visibleRevisions("bad-id")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.visibleRevisions(null)).status());
    }

    @Test
    void correctDeniesUnauthorizedAndAudits() {
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 2, "why")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_CORRECT", "rel-1", "DENIED");
    }

    @Test
    void correctConcealsUnknownRelease() {
        authorized("clinician");
        when(repository.findRelease("rel-1")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 2, "why")).status());
    }

    @Test
    void correctRejectsStaleVersion() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 4)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 2, "why")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_CORRECT", "rel-1", "STALE");
    }

    @Test
    void correctRejectsTerminalState() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.CORRECTED, NOW.toString(), 2)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 2, "LAB-REV-0002", 2, "why")).status());
    }

    @Test
    void correctRejectsHeldRelease() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.HELD, NOW.toString(), 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 2, "why")).status());
        verify(repository, never()).correct("rel-1", 1);
    }

    @Test
    void correctRejectsConcurrentUpdate() {
        authorized("clinician");
        when(repository.findRelease("rel-1"))
                .thenReturn(Optional.of(release(ReleaseState.AUTHORIZED, NOW.toString(), 1)));
        when(repository.correct("rel-1", 1)).thenReturn(0);
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.correct("rel-1", 1, "LAB-REV-0002", 2, "why")).status());
    }

    @Test
    void historyDeniesUnauthorizedReader() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of());
        assertEquals(403, assertThrows(ApiException.class, () -> service.history("rel-1")).status());
        verify(repository).appendAudit(TenantContext.current(), "RELEASE_HISTORY", "rel-1", "DENIED");
    }
}
