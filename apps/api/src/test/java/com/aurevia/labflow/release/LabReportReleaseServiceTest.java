package com.aurevia.labflow.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.release.AuthorizeRelease;
import com.aurevia.exchange.release.PatientRelease;
import com.aurevia.exchange.release.ReleaseEvent;
import com.aurevia.exchange.release.ReleaseKind;
import com.aurevia.exchange.release.ReleaseService;
import com.aurevia.exchange.release.ReleaseState;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.tenant.TenantContext;

/** LAB-02 / PAT-02 release policy, correction and withdrawal orchestration. */
class LabReportReleaseServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "clinician-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String RELEASE = "33333333-3333-3333-3333-333333333333";

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-21T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private ReleaseService releases;
    private LabAccessionRepository accessions;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private MutableClock clock;
    private LabReportReleaseService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "LAB_REPORT_RELEASE", "corr-1"));
        releases = mock(ReleaseService.class);
        accessions = mock(LabAccessionRepository.class);
        when(accessions.findById(any())).thenReturn(Optional.of(new LabAccession(
                "specimen-1", 2, "ACC-1", "LAB-A", PATIENT, "ORD/1", AccessionState.COLLECTED,
                "PLASMA", "SITE-A", "collector", "2026-11-21T09:00:00Z", null, "acceptable",
                null, null, null)));
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        clock = new MutableClock();
        service = new LabReportReleaseService(releases, accessions, audit, transactions, clock);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static PatientRelease release(ReleaseState state, String revisionId, int version,
                                           Instant effectiveFrom, String supersedesId) {
        return new PatientRelease(RELEASE, ORG, PATIENT, revisionId, version, ORG, 1,
                ReleaseKind.TIMED, state, effectiveFrom, null, null, null, null,
                supersedesId, 1, SUBJECT, Instant.parse("2026-11-21T10:00:00Z"));
    }

    private static ReleaseReportCommand command(FacilityReleasePolicy policy) {
        return new ReleaseReportCommand("specimen-1", "specimen-1/v2", 2, PATIENT, policy);
    }

    @Test
    void releaseEmitsClinicianAndPatientNotificationsForAnImmediatePolicy() {
        PatientRelease created = release(ReleaseState.AUTHORIZED, "specimen-1/v2", 2, clock.instant(), null);
        when(releases.authorize(any())).thenReturn(created);

        ReleasedReportView view = service.release(command(
                new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, Duration.ZERO)));

        assertTrue(view.patientVisibleNow());
        assertNull(view.correctionContext());
        assertNull(view.withdrawalNotice());
        ArgumentCaptor<AuthorizeRelease> captor = ArgumentCaptor.forClass(AuthorizeRelease.class);
        verify(releases).authorize(captor.capture());
        assertEquals("IMMEDIATE", captor.getValue().kind());
        assertNull(captor.getValue().effectiveFrom());
        assertNull(captor.getValue().holdReason());
        verify(audit).append(any(), any(), any(), any(), any(), any(), any());
        Set<String> scopes = service.history(RELEASE).stream()
                .filter(entry -> "VIEWER_NOTIFICATION".equals(entry.type()))
                .map(ReleaseHistoryEntry::action)
                .collect(Collectors.toSet());
        assertTrue(scopes.contains("RELEASE_CLINICIAN"));
        assertTrue(scopes.contains("RELEASE_PATIENT"));
    }

    @Test
    void timedPolicyDelaysPatientVisibilityUntilTheConfiguredDelayPasses() {
        FacilityReleasePolicy policy = new FacilityReleasePolicy(1, ReleaseKind.TIMED, Duration.ofHours(1));
        PatientRelease created = release(ReleaseState.AUTHORIZED, "specimen-1/v2", 2,
                policy.patientEffectiveFrom(clock.instant()), null);
        when(releases.authorize(any())).thenReturn(created);
        when(releases.get(RELEASE)).thenReturn(created);

        ReleasedReportView initial = service.release(command(policy));
        assertFalse(initial.patientVisibleNow());

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertTrue(service.read(RELEASE).patientVisibleNow());
        ArgumentCaptor<AuthorizeRelease> captor = ArgumentCaptor.forClass(AuthorizeRelease.class);
        verify(releases).authorize(captor.capture());
        assertEquals("TIMED", captor.getValue().kind());
        assertNotNull(captor.getValue().effectiveFrom());
        assertNull(captor.getValue().holdReason());
    }

    @Test
    void holdPolicyRequiresReviewAndNeverOpensByTimeAlone() {
        FacilityReleasePolicy policy = new FacilityReleasePolicy(1, ReleaseKind.HOLD, Duration.ofHours(1));
        PatientRelease held = release(ReleaseState.HELD, "specimen-1/v2", 2, clock.instant(), null);
        when(releases.authorize(any())).thenReturn(held);

        ReleasedReportView view = service.release(command(policy));

        assertFalse(view.patientVisibleNow());
        assertTrue(policy.reviewRequired());
        ArgumentCaptor<AuthorizeRelease> captor = ArgumentCaptor.forClass(AuthorizeRelease.class);
        verify(releases).authorize(captor.capture());
        assertEquals("HOLD", captor.getValue().kind());
        assertEquals("facility-policy-review", captor.getValue().holdReason());
        assertEquals(policy.patientEffectiveFrom(Instant.parse("2026-11-21T10:00:00Z")).toString(),
                captor.getValue().holdExpiresAt());
    }

    @Test
    void releaseConcealsAReportThatIsNotOwnedByTheActiveTenant() {
        when(accessions.findById(any())).thenReturn(Optional.empty());
        ApiException denial = assertThrows(ApiException.class, () -> service.release(command(
                new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, Duration.ZERO))));
        assertEquals(404, denial.status());
    }

    @Test
    void correctionNotifiesEarlierViewersAndCarriesTheChangeContext() {
        PatientRelease created = release(ReleaseState.AUTHORIZED, "specimen-1/v2", 2, clock.instant(), null);
        when(releases.authorize(any())).thenReturn(created);
        service.release(command(new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, Duration.ZERO)));

        PatientRelease corrected = release(ReleaseState.AUTHORIZED, "specimen-1/v3", 3,
                clock.instant(), RELEASE);
        when(releases.correct(RELEASE, 1, "specimen-1/v3", 3, "GLU corrected"))
                .thenReturn(corrected);

        ReleasedReportView view = service.correct(new CorrectReleaseCommand(
                RELEASE, 1, "specimen-1/v3", 3, "GLU corrected"));

        assertNotNull(view.correctionContext());
        assertTrue(view.correctionContext().contains("GLU corrected"));
        assertNull(view.withdrawalNotice());
        List<ReleaseHistoryEntry> correctionNotices = service.history(RELEASE).stream()
                .filter(entry -> "CORRECTION_NOTICE".equals(entry.action()))
                .toList();
        assertEquals(2, correctionNotices.size());
        assertEquals(Set.of("ordering-clinician", "linked-patient"),
                correctionNotices.stream().map(ReleaseHistoryEntry::actor).collect(Collectors.toSet()));
    }

    @Test
    void correctionFallsBackToClinicianAndPatientWhenThereAreNoEarlierViewers() {
        PatientRelease corrected = release(ReleaseState.AUTHORIZED, "specimen-1/v3", 3,
                clock.instant(), RELEASE);
        when(releases.correct(RELEASE, 1, "specimen-1/v3", 3, "reason"))
                .thenReturn(corrected);

        service.correct(new CorrectReleaseCommand(RELEASE, 1, "specimen-1/v3", 3, "reason"));

        assertEquals(2, service.history(RELEASE).stream()
                .filter(entry -> "CORRECTION_NOTICE".equals(entry.action())).count());
    }

    @Test
    void withdrawnReportShowsANoticeAndNotifiesEarlierViewers() {
        PatientRelease created = release(ReleaseState.AUTHORIZED, "specimen-1/v2", 2, clock.instant(), null);
        when(releases.authorize(any())).thenReturn(created);
        service.release(command(new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, Duration.ZERO)));

        PatientRelease withdrawn = release(ReleaseState.WITHDRAWN, "specimen-1/v2", 2,
                clock.instant(), null);
        when(releases.withdraw(RELEASE, 1, "wrong patient")).thenReturn(withdrawn);

        ReleasedReportView view = service.withdraw(
                new WithdrawReleaseCommand(RELEASE, 1, "wrong patient"));

        assertNotNull(view.withdrawalNotice());
        assertTrue(view.withdrawalNotice().contains("wrong patient"));
        assertTrue(service.isWithdrawn(view));
        assertEquals(2, service.history(RELEASE).stream()
                .filter(entry -> "WITHDRAWAL_NOTICE".equals(entry.action())).count());
    }

    @Test
    void readProjectsWithdrawalNoticeAndCorrectionContext() {
        when(releases.get(RELEASE)).thenReturn(
                release(ReleaseState.WITHDRAWN, "specimen-1/v2", 2, clock.instant(), null));
        assertTrue(service.isWithdrawn(service.read(RELEASE)));

        when(releases.get(RELEASE)).thenReturn(
                release(ReleaseState.AUTHORIZED, "specimen-1/v3", 3, clock.instant(), "older-release"));
        ReleasedReportView corrected = service.read(RELEASE);
        assertFalse(service.isWithdrawn(corrected));
        assertTrue(corrected.correctionContext().contains("older-release"));

        when(releases.get(RELEASE)).thenReturn(
                release(ReleaseState.AUTHORIZED, "specimen-1/v2", 2, clock.instant(), null));
        ReleasedReportView current = service.read(RELEASE);
        assertNull(current.correctionContext());
        assertNull(current.withdrawalNotice());
    }

    @Test
    void historyCombinesReleaseEventsAndViewerNotifications() {
        when(releases.history(RELEASE)).thenReturn(List.of(new ReleaseEvent(
                "event-1", RELEASE, "RELEASE_AUTHORIZE", null, "AUTHORIZED", null,
                SUBJECT, clock.instant())));
        PatientRelease created = release(ReleaseState.AUTHORIZED, "specimen-1/v2", 2, clock.instant(), null);
        when(releases.authorize(any())).thenReturn(created);
        service.release(command(new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, Duration.ZERO)));

        List<ReleaseHistoryEntry> history = service.history(RELEASE);

        assertTrue(history.stream().anyMatch(entry -> "RELEASE_EVENT".equals(entry.type())));
        assertTrue(history.stream().anyMatch(entry -> "VIEWER_NOTIFICATION".equals(entry.type())));
        assertTrue(service.history("unknown-release").isEmpty());
    }

    @Test
    void invalidFacilityPolicyIsRejected() {
        assertThrows(ApiException.class, () -> new FacilityReleasePolicy(0, ReleaseKind.IMMEDIATE, Duration.ZERO));
        assertThrows(ApiException.class, () -> new FacilityReleasePolicy(1, null, Duration.ZERO));
        assertThrows(ApiException.class, () -> new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, Duration.ofSeconds(-1)));
        assertThrows(ApiException.class, () -> new FacilityReleasePolicy(1, ReleaseKind.HOLD, Duration.ZERO));
        FacilityReleasePolicy defaults = new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, null);
        assertEquals(Duration.ZERO, defaults.patientDelay());
        assertFalse(defaults.reviewRequired());
    }

    @Test
    void invalidCommandsAreRejected() {
        FacilityReleasePolicy policy = new FacilityReleasePolicy(1, ReleaseKind.IMMEDIATE, Duration.ZERO);
        assertThrows(ApiException.class, () -> new ReleaseReportCommand(null, "r/v1", 1, PATIENT, policy));
        assertThrows(ApiException.class, () -> new ReleaseReportCommand("", "r/v1", 1, PATIENT, policy));
        assertThrows(ApiException.class, () -> new ReleaseReportCommand("r", null, 1, PATIENT, policy));
        assertThrows(ApiException.class, () -> new ReleaseReportCommand("r", "", 1, PATIENT, policy));
        assertThrows(ApiException.class, () -> new ReleaseReportCommand("r", "r/v1", 0, PATIENT, policy));
        assertThrows(ApiException.class, () -> new ReleaseReportCommand("r", "r/v1", 1, null, policy));
        assertThrows(ApiException.class, () -> new ReleaseReportCommand("r", "r/v1", 1, "", policy));
        assertThrows(ApiException.class, () -> new ReleaseReportCommand("r", "r/v1", 1, PATIENT, null));

        assertThrows(ApiException.class, () -> new CorrectReleaseCommand(null, 1, "r/v2", 2, "why"));
        assertThrows(ApiException.class, () -> new CorrectReleaseCommand("", 1, "r/v2", 2, "why"));
        assertThrows(ApiException.class, () -> new CorrectReleaseCommand("rel", 0, "r/v2", 2, "why"));
        assertThrows(ApiException.class, () -> new CorrectReleaseCommand("rel", 1, null, 2, "why"));
        assertThrows(ApiException.class, () -> new CorrectReleaseCommand("rel", 1, "", 2, "why"));
        assertThrows(ApiException.class, () -> new CorrectReleaseCommand("rel", 1, "r/v2", 0, "why"));
        assertThrows(ApiException.class, () -> new CorrectReleaseCommand("rel", 1, "r/v2", 2, null));
        assertThrows(ApiException.class, () -> new CorrectReleaseCommand("rel", 1, "r/v2", 2, " "));

        assertThrows(ApiException.class, () -> new WithdrawReleaseCommand(null, 1, "why"));
        assertThrows(ApiException.class, () -> new WithdrawReleaseCommand("", 1, "why"));
        assertThrows(ApiException.class, () -> new WithdrawReleaseCommand("rel", 0, "why"));
        assertThrows(ApiException.class, () -> new WithdrawReleaseCommand("rel", 1, null));
        assertThrows(ApiException.class, () -> new WithdrawReleaseCommand("rel", 1, ""));
    }
}
