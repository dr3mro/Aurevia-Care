package com.aurevia.pharmacyflow.clarify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.clarify.ClarificationCommands.AnswerCommand;
import com.aurevia.pharmacyflow.clarify.ClarificationCommands.OpenCommand;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.Entry;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.Party;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.State;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.ThreadView;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.View;
import com.aurevia.prescription.rx.RxService;

/** RX-02 / MSG-04 clarification threads: closure, revision link, overdue and party isolation. */
class ClarificationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "99999999-9999-9999-9999-999999999999";
    private static final String PHARMACIST = "pharm-tech";
    private static final String PRESCRIBER = "dr-prescriber";
    private static final String OTHER_CLINICIAN = "dr-other";
    private static final String PRESCRIPTION = "rx-1";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Duration LIMIT = Duration.ofHours(24);
    private static final Set<String> PHARMACY_ROLES = Set.of(SpecialtyRoles.PHARMACY_STAFF);
    private static final Set<String> CLINICIAN_ROLES = Set.of(SpecialtyRoles.CLINICIAN);
    private static final Set<String> RECEPTION_ROLES = Set.of(SpecialtyRoles.RECEPTION);

    private ClarificationService service;
    private RxService prescriptions;
    private AdvancingClock clock;

    @BeforeEach
    void setUp() {
        prescriptions = mock(RxService.class);
        stubPrescription(PRESCRIBER);
        clock = new AdvancingClock(AT);
        service = new ClarificationService(clock, LIMIT, prescriptions);
    }

    private void stubPrescription(String prescriber) {
        View view = new View(new Prescription(PRESCRIPTION, 1, "patient-1", "paracetamol", "500mg",
                "1 tablet", "oral", "twice daily", 60, prescriber, "pharmacy-a",
                com.aurevia.prescription.rx.RxContract.State.DELIVERY_CONFIRMED,
                "partner", "submission-1", false, AT), List.of());
        when(prescriptions.get(anyString())).thenReturn(view);
    }

    private OpenCommand open(String org, String subject, Set<String> roles, String prescription,
                             String question, String key) {
        return new OpenCommand(org, subject, roles, prescription, question, key);
    }

    private OpenCommand open() {
        return open(ORG, PHARMACIST, PHARMACY_ROLES, PRESCRIPTION, "confirm the dose", "open-1");
    }

    private AnswerCommand answer(String org, String subject, Set<String> roles, String threadId,
                                 int version, String body, String revision, String key) {
        return new AnswerCommand(org, subject, roles, threadId, version, body, revision, key);
    }

    private String openedThread() {
        return service.open(open()).thread().id();
    }

    @Test
    void wireKeysAreStable() {
        assertEquals("open", State.OPEN.wire());
        assertEquals("answered", State.ANSWERED.wire());
        assertEquals("pharmacy", Party.PHARMACY.wire());
        assertEquals("prescriber", Party.PRESCRIBER.wire());
    }

    @Test
    void openCreatesThreadAddressedToAuthoritativePrescriber() {
        ThreadView view = service.open(open());

        assertEquals(ORG, view.thread().organizationId());
        assertEquals(PRESCRIPTION, view.thread().prescriptionId());
        assertEquals(PHARMACIST, view.thread().requesterSubject());
        assertEquals(PRESCRIBER, view.thread().prescriberSubject());
        assertEquals(State.OPEN, view.thread().state());
        assertEquals(AT, view.thread().openedAt());
        assertEquals(1, view.thread().version());
        assertNull(view.thread().answeredAt());
        assertEquals(1, view.entries().size());
        Entry entry = view.entries().get(0);
        assertEquals(Party.PHARMACY, entry.authorParty());
        assertEquals(PHARMACIST, entry.authorSubject());
        assertEquals("confirm the dose", entry.body());
        assertNull(entry.amendedRevisionId());
        assertEquals(AT, entry.occurredAt());
        assertEquals(1, entry.sequence());
        assertTrue(entry.id().length() > 0);
        assertTrue(!view.overdue());
    }

    @Test
    void openRequiresValidFields() {
        assertThrows(ApiException.class, () -> service.open(open(" ", PHARMACIST, PHARMACY_ROLES,
                PRESCRIPTION, "q", "k")));
        assertThrows(ApiException.class, () -> service.open(open(ORG, " ", PHARMACY_ROLES,
                PRESCRIPTION, "q", "k")));
        assertThrows(ApiException.class, () -> service.open(open(ORG, PHARMACIST, PHARMACY_ROLES,
                " ", "q", "k")));
        assertThrows(ApiException.class, () -> service.open(open(ORG, PHARMACIST, PHARMACY_ROLES,
                PRESCRIPTION, null, "k")));
        assertThrows(ApiException.class, () -> service.open(open(ORG, PHARMACIST, PHARMACY_ROLES,
                PRESCRIPTION, "  ", "k")));
        assertThrows(ApiException.class, () -> service.open(open(ORG, PHARMACIST, PHARMACY_ROLES,
                PRESCRIPTION, "q", null)));
    }

    @Test
    void openRequiresPharmacyRole() {
        ApiException denial = assertThrows(ApiException.class,
                () -> service.open(open(ORG, PHARMACIST, CLINICIAN_ROLES, PRESCRIPTION, "q", "k")));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(service.overdue(ORG, PRESCRIBER, CLINICIAN_ROLES).isEmpty());
    }

    @Test
    void openFailsWhenPrescriptionIsNotVisible() {
        when(prescriptions.get(PRESCRIPTION))
                .thenThrow(new ApiException(404, "NOT_FOUND", "prescription not found"));

        ApiException denial = assertThrows(ApiException.class, () -> service.open(open()));

        assertEquals(404, denial.status());
        assertTrue(service.overdue(ORG, PHARMACIST, PHARMACY_ROLES).isEmpty());
    }

    @Test
    void openReplaysByIdempotencyKey() {
        ThreadView first = service.open(open());
        ThreadView retry = service.open(open());

        assertEquals(first.thread().id(), retry.thread().id());
        assertEquals(1, retry.entries().size());
        clock.advance(LIMIT.plusNanos(1));
        assertEquals(1, service.overdue(ORG, PHARMACIST, PHARMACY_ROLES).size());
    }

    @Test
    void distinctIdempotencyKeysOpenDistinctThreads() {
        service.open(open());
        service.open(open(ORG, PHARMACIST, PHARMACY_ROLES, PRESCRIPTION, "again", "open-2"));

        clock.advance(LIMIT.plusNanos(1));
        assertEquals(2, service.overdue(ORG, PHARMACIST, PHARMACY_ROLES).size());
    }

    @Test
    void answerWithTextClosesRequestAndReachesPharmacy() {
        String threadId = openedThread();

        ThreadView answered = service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 1,
                "give 250mg in the morning", null, "answer-1"));

        assertEquals(State.ANSWERED, answered.thread().state());
        assertEquals("give 250mg in the morning", answered.thread().answerBody());
        assertNull(answered.thread().amendedRevisionId());
        assertEquals(AT, answered.thread().answeredAt());
        assertEquals(PRESCRIBER, answered.thread().answeredBy());
        assertEquals(2, answered.thread().version());
        assertEquals(2, answered.entries().size());
        Entry reply = answered.entries().get(1);
        assertEquals(Party.PRESCRIBER, reply.authorParty());
        assertEquals(PRESCRIBER, reply.authorSubject());
        assertEquals("give 250mg in the morning", reply.body());
        assertNull(reply.amendedRevisionId());
        assertEquals(2, reply.sequence());

        ThreadView pharmacyView = service.view(ORG, threadId, PHARMACIST, PHARMACY_ROLES);
        assertEquals(State.ANSWERED, pharmacyView.thread().state());
        assertEquals("give 250mg in the morning", pharmacyView.thread().answerBody());
        assertEquals(2, pharmacyView.entries().size());
    }

    @Test
    void answerWithAmendedRevisionLinksRevision() {
        String threadId = openedThread();

        ThreadView answered = service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 1,
                null, "revision-7", "answer-rev"));

        assertEquals(State.ANSWERED, answered.thread().state());
        assertNull(answered.thread().answerBody());
        assertEquals("revision-7", answered.thread().amendedRevisionId());
        assertEquals("revision-7", answered.entries().get(1).amendedRevisionId());
        assertNull(answered.entries().get(1).body());
    }

    @Test
    void answerWithBothTextAndRevisionKeepsBoth() {
        String threadId = openedThread();

        ThreadView answered = service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 1,
                "see amended script", "revision-8", "answer-both"));

        assertEquals("see amended script", answered.thread().answerBody());
        assertEquals("revision-8", answered.thread().amendedRevisionId());
    }

    @Test
    void answerRequiresTextOrRevisionAndValidPreconditions() {
        String threadId = openedThread();
        ApiException missing = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 1, " ", "  ", "a-miss")));
        assertEquals(422, missing.status());
        assertEquals("ANSWER_REQUIRED", missing.code());

        ApiException stale = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 0, "text", null, "a-zero")));
        assertEquals(422, stale.status());

        ApiException key = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 1, "text", null, " ")));
        assertEquals(422, key.status());

        ApiException org = assertThrows(ApiException.class,
                () -> service.answer(answer(" ", PRESCRIBER, CLINICIAN_ROLES, threadId, 1, "text", null, "a-org")));
        assertEquals(422, org.status());

        ApiException subject = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, " ", CLINICIAN_ROLES, threadId, 1, "text", null, "a-sub")));
        assertEquals(422, subject.status());

        ApiException thread = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, " ", 1, "text", null, "a-thread")));
        assertEquals(422, thread.status());

        assertEquals(1, service.view(ORG, threadId, PHARMACIST, PHARMACY_ROLES).entries().size());
    }

    @Test
    void answerRequiresPrescriberRole() {
        String threadId = openedThread();
        ApiException denial = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, PHARMACY_ROLES, threadId, 1, "text", null, "a-role")));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals(State.OPEN, service.view(ORG, threadId, PHARMACIST, PHARMACY_ROLES).thread().state());
    }

    @Test
    void answerConcealsThreadFromUnknownThreadAndOtherTenant() {
        assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, "missing", 1, "text", null, "a-1")));
        assertThrows(ApiException.class,
                () -> service.answer(answer(OTHER_ORG, PRESCRIBER, CLINICIAN_ROLES, openedThread(), 1,
                        "text", null, "a-2")));
    }

    @Test
    void answerConcealsThreadFromAnotherClinician() {
        String threadId = openedThread();
        ApiException denial = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, OTHER_CLINICIAN, CLINICIAN_ROLES, threadId, 1,
                        "text", null, "a-other")));

        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
    }

    @Test
    void answerRejectsStaleVersionAndClosedThread() {
        String threadId = openedThread();
        ApiException stale = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 2,
                        "text", null, "a-stale")));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());

        service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 1, "text", null, "a-ok"));
        ApiException closed = assertThrows(ApiException.class,
                () -> service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 2,
                        "again", null, "a-closed")));
        assertEquals(409, closed.status());
        assertEquals("CLOSED_THREAD", closed.code());
    }

    @Test
    void answerReplaysByIdempotencyKey() {
        String threadId = openedThread();
        AnswerCommand retry = answer(ORG, PRESCRIBER, CLINICIAN_ROLES, threadId, 1, "text", null, "answer-same");

        ThreadView first = service.answer(retry);
        ThreadView again = service.answer(retry);

        assertEquals(first.thread().id(), again.thread().id());
        assertEquals(2, again.entries().size());
        assertEquals(2, again.thread().version());
    }

    @Test
    void sameAnswerKeyOnAnotherThreadIsNotAReplay() {
        String firstThread = openedThread();
        String secondThread = service.open(open(ORG, PHARMACIST, PHARMACY_ROLES, PRESCRIPTION,
                "second question", "open-2")).thread().id();

        service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, firstThread, 1, "text", null, "shared-key"));
        service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, secondThread, 1, "text", null, "shared-key"));

        assertEquals(2, service.view(ORG, secondThread, PHARMACIST, PHARMACY_ROLES).entries().size());
    }

    @Test
    void viewValidatesScope() {
        String threadId = openedThread();
        assertThrows(ApiException.class, () -> service.view(" ", threadId, PHARMACIST, PHARMACY_ROLES));
        assertThrows(ApiException.class, () -> service.view(ORG, " ", PHARMACIST, PHARMACY_ROLES));
        assertThrows(ApiException.class, () -> service.view(ORG, threadId, " ", PHARMACY_ROLES));
    }

    @Test
    void viewConcealsThreadFromOtherTenantAndUnknownThread() {
        String threadId = openedThread();
        ApiException otherTenant = assertThrows(ApiException.class,
                () -> service.view(OTHER_ORG, threadId, PHARMACIST, PHARMACY_ROLES));
        assertEquals(404, otherTenant.status());
        assertThrows(ApiException.class, () -> service.view(ORG, "missing", PHARMACIST, PHARMACY_ROLES));
    }

    @Test
    void viewConcealsThreadFromThirdPartyAndWrongRole() {
        String threadId = openedThread();
        ApiException thirdParty = assertThrows(ApiException.class,
                () -> service.view(ORG, threadId, OTHER_CLINICIAN, CLINICIAN_ROLES));
        assertEquals(404, thirdParty.status());

        assertThrows(ApiException.class, () -> service.view(ORG, threadId, PHARMACIST, CLINICIAN_ROLES));
        assertThrows(ApiException.class, () -> service.view(ORG, threadId, PRESCRIBER, PHARMACY_ROLES));
    }

    @Test
    void bothPartiesInTheTenantCanViewTheThread() {
        String threadId = openedThread();

        assertEquals(threadId, service.view(ORG, threadId, PHARMACIST, PHARMACY_ROLES).thread().id());
        assertEquals(threadId, service.view(ORG, threadId, PRESCRIBER, CLINICIAN_ROLES).thread().id());
    }

    @Test
    void overdueListsOnlyOldOpenThreadsVisibleToTheCaller() {
        String answeredId = openedThread();
        service.answer(answer(ORG, PRESCRIBER, CLINICIAN_ROLES, answeredId, 1, "done", null, "answer-old"));
        String oldOpenId = service.open(open(ORG, PHARMACIST, PHARMACY_ROLES, PRESCRIPTION,
                "open question", "open-old")).thread().id();
        service.open(open(ORG, "another-pharm", PHARMACY_ROLES, PRESCRIPTION,
                "other", "open-other"));

        clock.advance(LIMIT.plusNanos(1));
        service.open(open(ORG, PHARMACIST, PHARMACY_ROLES, PRESCRIPTION, "fresh question", "open-fresh"));
        List<ThreadView> overdue = service.overdue(ORG, PHARMACIST, PHARMACY_ROLES);

        assertEquals(List.of(oldOpenId), overdue.stream().map(v -> v.thread().id()).toList());
        for (ThreadView view : overdue) {
            assertTrue(view.overdue());
            assertEquals(State.OPEN, view.thread().state());
        }
    }

    @Test
    void overdueBoundaryIsStrictlyOlderThanTheLimit() {
        String threadId = openedThread();
        clock.advance(LIMIT);
        assertTrue(service.overdue(ORG, PHARMACIST, PHARMACY_ROLES).isEmpty());

        clock.advance(Duration.ofNanos(1));
        assertTrue(service.overdue(ORG, PHARMACIST, PHARMACY_ROLES).stream()
                .anyMatch(view -> view.thread().id().equals(threadId)));
    }

    @Test
    void overdueCanBeReadByThePrescriberParty() {
        openedThread();
        clock.advance(LIMIT.plusNanos(1));

        List<ThreadView> overdue = service.overdue(ORG, PRESCRIBER, CLINICIAN_ROLES);
        assertEquals(1, overdue.size());
        assertTrue(overdue.get(0).overdue());
    }

    @Test
    void overdueRequiresStaffRoleAndValidScope() {
        assertThrows(ApiException.class, () -> service.overdue(ORG, PRESCRIBER, RECEPTION_ROLES));
        assertThrows(ApiException.class, () -> service.overdue(" ", PRESCRIBER, CLINICIAN_ROLES));
        assertThrows(ApiException.class, () -> service.overdue(ORG, " ", CLINICIAN_ROLES));
    }

    @Test
    void defaultOverdueLimitIsConfiguredInTheDomain() {
        AdvancingClock defaultClock = new AdvancingClock(AT);
        ClarificationService defaults = new ClarificationService(defaultClock, prescriptions);
        String threadId = defaults.open(open()).thread().id();
        defaultClock.advance(Duration.ofHours(49));

        assertTrue(defaults.overdue(ORG, PHARMACIST, PHARMACY_ROLES).stream()
                .anyMatch(view -> view.thread().id().equals(threadId)));
    }

    @Test
    void threadViewEntriesAreFrozen() {
        ThreadView view = service.open(open());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
    }

    @Test
    void roleSetsAreCopiedAtConstruction() {
        Set<String> mutable = new HashSet<>(PHARMACY_ROLES);
        OpenCommand openCommand = open(ORG, PHARMACIST, mutable, PRESCRIPTION, "q", "k");
        mutable.clear();
        assertEquals(PHARMACY_ROLES, openCommand.roles());
        assertEquals(PHARMACIST, service.open(openCommand).thread().requesterSubject());

        Set<String> mutableClinician = new HashSet<>(CLINICIAN_ROLES);
        AnswerCommand answerCommand = answer(ORG, PRESCRIBER, mutableClinician, openedThread(), 1,
                "text", null, "answer-copy");
        mutableClinician.clear();
        assertEquals(CLINICIAN_ROLES, answerCommand.roles());
        assertEquals(State.ANSWERED, service.answer(answerCommand).thread().state());
    }

    /** Mutable test clock so one service instance can cross the overdue boundary. */
    private static final class AdvancingClock extends Clock {
        private Instant now;

        private AdvancingClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

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
    }
}
