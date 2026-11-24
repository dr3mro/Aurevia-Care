package com.aurevia.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.LabCommands.CaptureCommand;
import com.aurevia.lab.result.LabCommands.CorrectCommand;
import com.aurevia.lab.result.LabCommands.ErrorCommand;
import com.aurevia.lab.result.LabCommands.VerifyCommand;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabPolicyCatalog;
import com.aurevia.lab.result.LabPolicyCatalog.ReferenceRange;
import com.aurevia.lab.result.LabResultService;
import com.aurevia.lab.result.LabRules;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;
import com.aurevia.lab.result.LabTypes.TriggerType;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.lab.result.ReportRevision;
import com.aurevia.lab.result.TriggerEvent;

/**
 * LAB-02 clause evidence: a report moves through preliminary, final, corrected and
 * entered-in-error revisions, and every refusal leaves the append-only history intact.
 *
 * <p>All identifiers and actors are synthetic. The fixed clock makes effective times and
 * event ordering deterministic; the service is the in-memory domain kernel used by the
 * neighbouring {@code it.lab} fixtures, so no Spring context or database is required.</p>
 */
class SupportPreliminaryFinalCorrectedEnteredErrorReportsTest {

    private static final String ORG = "org-a";
    private static final String OTHER_ORG = "org-b";
    private static final String REPORT = "report-a";
    private static final String PATIENT = "patient-a";
    private static final String SPECIMEN = "specimen-a";
    private static final String ORDER = "order-a";
    private static final String ACTOR = "synthetic-collector";
    private static final String VERIFIER = "synthetic-verifier";
    private static final String OWNER = "synthetic-owner";
    private static final String QUEUE = "synthetic-coverage-queue";
    private static final String REASON = "transcription fix";
    private static final String WITHDRAWAL_REASON = "wrong specimen";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private LabResultService service;

    /** Build a fresh kernel with one approved glucose range and a fixed application clock. */
    @BeforeEach
    void setUp() {
        LabPolicyCatalog policies = new LabPolicyCatalog(
                List.of(new ReferenceRange("GLU", "mg/dL", new BigDecimal("70"), new BigDecimal("110"),
                        2, true, FROM, null)),
                List.of());
        service = new LabResultService(policies, Clock.fixed(AT, ZoneOffset.UTC));
    }

    /**
     * Build one exact numeric observation with an explicit normal abnormal flag.
     *
     * @param code  test code
     * @param value measured value
     * @return synthetic observation
     */
    private static LabObservation numeric(String code, String value) {
        return new LabObservation(code, ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal(value), Qualifier.NONE, "mg/dL", null, null, null,
                AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    /**
     * Build a capture command using the synthetic collector and matched labels.
     *
     * @param org          active tenant
     * @param roles        roles active for the actor
     * @param observations captured observations
     * @return capture command
     */
    private static CaptureCommand capture(String org, Set<String> roles, List<LabObservation> observations) {
        return new CaptureCommand(org, ACTOR, roles, REPORT, PATIENT, SPECIMEN, ORDER, observations);
    }

    /**
     * Build a verify command using the synthetic verifier, owner and queue.
     *
     * @param org     active tenant
     * @param roles   roles active for the actor
     * @param version expected current version
     * @return verify command
     */
    private static VerifyCommand verify(String org, Set<String> roles, int version) {
        return new VerifyCommand(org, VERIFIER, roles, REPORT, version, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE);
    }

    /**
     * Build a correction command with an explicit reason.
     *
     * @param org     active tenant
     * @param roles   roles active for the actor
     * @param version expected current version
     * @param reason  correction reason
     * @return correction command
     */
    private static CorrectCommand correct(String org, Set<String> roles, int version, String reason) {
        return new CorrectCommand(org, VERIFIER, roles, REPORT, version, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, reason);
    }

    /**
     * Build an entered-in-error command with an explicit reason.
     *
     * @param org     active tenant
     * @param roles   roles active for the actor
     * @param version expected current version
     * @param reason  withdrawal reason
     * @return error command
     */
    private static ErrorCommand error(String org, Set<String> roles, int version, String reason) {
        return new ErrorCommand(org, VERIFIER, roles, REPORT, version, reason);
    }

    /** Capture the first synthetic preliminary revision. */
    private ReportRevision captured() {
        return service.capture(capture(ORG, Set.of(LabRules.COLLECTOR), List.of(numeric("GLU", "90"))));
    }

    /** Capture and verify a preliminary revision into its final issued revision. */
    private ReportRevision issued() {
        ReportRevision preliminary = captured();
        return service.verify(verify(ORG, Set.of(LabRules.VERIFIER), preliminary.version()));
    }

    /**
     * Rule met: all four report states are appended in order, each linked to the version it
     * supersedes, with the issued revisions routing one distinct event each in emission order.
     */
    @Test
    void preliminaryFinalCorrectedAndEnteredInErrorRevisionsAreAppendedInOrder() {
        ReportRevision preliminary = captured();
        assertEquals(VerificationState.PRELIMINARY, preliminary.state());
        assertEquals(1, preliminary.version());
        assertNull(preliminary.supersedesVersion());
        assertNull(preliminary.verifier());
        assertNull(preliminary.effectiveAt());

        ReportRevision finalRevision = service.verify(
                verify(ORG, Set.of(LabRules.VERIFIER), preliminary.version()));
        assertEquals(VerificationState.FINAL, finalRevision.state());
        assertEquals(2, finalRevision.version());
        assertEquals(Integer.valueOf(1), finalRevision.supersedesVersion());
        assertEquals(VERIFIER, finalRevision.verifier());
        assertEquals(AT, finalRevision.effectiveAt());
        assertNull(finalRevision.reason());

        ReportRevision corrected = service.correct(
                correct(ORG, Set.of(LabRules.VERIFIER), finalRevision.version(), REASON));
        assertEquals(VerificationState.CORRECTED, corrected.state());
        assertEquals(3, corrected.version());
        assertEquals(Integer.valueOf(2), corrected.supersedesVersion());
        assertEquals(REASON, corrected.reason());

        ReportRevision errored = service.enterInError(
                error(ORG, Set.of(LabRules.VERIFIER), corrected.version(), WITHDRAWAL_REASON));
        assertEquals(VerificationState.ENTERED_IN_ERROR, errored.state());
        assertEquals(4, errored.version());
        assertEquals(Integer.valueOf(3), errored.supersedesVersion());
        assertEquals(WITHDRAWAL_REASON, errored.reason());
        assertEquals(VerificationState.ENTERED_IN_ERROR, service.read(ORG, REPORT).state());

        List<ReportRevision> history = service.revisions(ORG, REPORT);
        assertEquals(4, history.size());
        assertEquals(List.of(VerificationState.PRELIMINARY, VerificationState.FINAL,
                VerificationState.CORRECTED, VerificationState.ENTERED_IN_ERROR),
                history.stream().map(ReportRevision::state).toList());
        assertEquals(List.of(1, 2, 3, 4), history.stream().map(ReportRevision::version).toList());
        assertEquals(VerificationState.PRELIMINARY, history.get(0).state(),
                "the preliminary revision is never mutated");
        assertEquals(VerificationState.FINAL, history.get(1).state(),
                "the issued revision survives the correction");
        assertEquals(VerificationState.CORRECTED, history.get(2).state(),
                "the corrected revision survives the withdrawal");
        assertNull(history.get(1).reason(), "a final revision carries no reason");
        assertEquals(AT, history.get(3).effectiveAt());

        List<TriggerEvent> events = service.events(ORG);
        assertEquals(3, events.size());
        assertEquals(List.of(TriggerType.RESULT_ISSUED, TriggerType.RESULT_CORRECTED,
                TriggerType.RESULT_WITHDRAWN), events.stream().map(TriggerEvent::type).toList());
        assertEquals(List.of(2, 3, 4), events.stream().map(TriggerEvent::reportVersion).toList());
        assertEquals(OWNER, events.get(0).owner());
        assertEquals(QUEUE, events.get(0).queue());
        assertEquals(PATIENT, events.get(0).patientId());
        assertEquals(ORDER, events.get(0).orderId());
        assertNull(events.get(2).owner(), "a withdrawal routes to no coverage owner");
        assertEquals(3, Set.copyOf(events.stream().map(TriggerEvent::eventId).toList()).size(),
                "each issued revision carries a distinct event identity");
    }

    /** Negative: a collector may capture but may not verify, correct or withdraw a report. */
    @Test
    void collectorRoleCannotVerifyCorrectOrWithdraw() {
        ReportRevision preliminary = captured();
        Set<String> collector = Set.of(LabRules.COLLECTOR);

        assertEquals(403, assertThrows(ApiException.class,
                () -> service.verify(verify(ORG, collector, preliminary.version()))).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.correct(correct(ORG, collector, preliminary.version(), REASON))).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.enterInError(error(ORG, collector, preliminary.version(), REASON))).status());

        assertEquals(1, service.revisions(ORG, REPORT).size());
        assertEquals(VerificationState.PRELIMINARY, service.read(ORG, REPORT).state());
        assertTrue(service.events(ORG).isEmpty());
    }

    /** Negative: another tenant can neither read nor mutate a report, and sees no events. */
    @Test
    void anotherTenantCannotReadOrMutateTheReport() {
        ReportRevision preliminary = captured();
        Set<String> verifier = Set.of(LabRules.VERIFIER);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.read(OTHER_ORG, REPORT)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.revisions(OTHER_ORG, REPORT)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.verify(verify(OTHER_ORG, verifier, preliminary.version()))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.correct(correct(OTHER_ORG, verifier, preliminary.version(), REASON))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.enterInError(error(OTHER_ORG, verifier, preliminary.version(), REASON))).status());

        assertTrue(service.events(OTHER_ORG).isEmpty());
        assertEquals(1, preliminary.version());
        assertEquals(1, service.revisions(ORG, REPORT).size());
    }

    /** Negative: a stale expected version cannot issue, correct or withdraw the current revision. */
    @Test
    void staleVersionCannotIssueCorrectOrWithdraw() {
        ReportRevision issued = issued();
        Set<String> verifier = Set.of(LabRules.VERIFIER);
        int stale = 1;

        assertEquals(412, assertThrows(ApiException.class,
                () -> service.verify(verify(ORG, verifier, stale))).status());
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.correct(correct(ORG, verifier, stale, REASON))).status());
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.enterInError(error(ORG, verifier, stale, REASON))).status());

        assertEquals(2, issued.version());
        assertEquals(2, service.revisions(ORG, REPORT).size());
        assertEquals(1, service.events(ORG).size(), "a refused transition emits no extra event");
    }

    /** Negative: entered-in-error is terminal and refuses every further transition. */
    @Test
    void enteredInErrorForbidsEveryFurtherTransition() {
        ReportRevision issued = issued();
        Set<String> verifier = Set.of(LabRules.VERIFIER);
        ReportRevision errored = service.enterInError(
                error(ORG, verifier, issued.version(), WITHDRAWAL_REASON));

        assertEquals(VerificationState.ENTERED_IN_ERROR, errored.state());
        assertEquals(3, errored.version());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.verify(verify(ORG, verifier, errored.version()))).status());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.correct(correct(ORG, verifier, errored.version(), REASON))).status());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.enterInError(error(ORG, verifier, errored.version(), REASON))).status());

        assertEquals(3, service.revisions(ORG, REPORT).size());
        assertEquals(2, service.events(ORG).size(), "no event is emitted for a refused transition");
        assertEquals(TriggerType.RESULT_WITHDRAWN, service.events(ORG).get(1).type());
    }

    /** Negative: blank reasons, verifiers, owners, queues and mismatched labels are refused. */
    @Test
    void missingReasonVerifierOrMismatchedLabelsAreRefused() {
        ReportRevision issued = issued();
        Set<String> verifier = Set.of(LabRules.VERIFIER);
        int current = issued.version();

        assertEquals(422, assertThrows(ApiException.class,
                () -> service.correct(correct(ORG, verifier, current, " "))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.enterInError(error(ORG, verifier, current, " "))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, verifier, REPORT, current, PATIENT, SPECIMEN, ORDER,
                " ", OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, verifier, REPORT, current, PATIENT, SPECIMEN, ORDER,
                " ", OWNER, QUEUE, REASON))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, verifier, REPORT, current, PATIENT, SPECIMEN, ORDER,
                VERIFIER, " ", QUEUE, REASON))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, verifier, REPORT, current, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, " ", REASON))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, verifier, REPORT, current, "other-patient", SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, REASON))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, verifier, REPORT, current, PATIENT, "other-specimen", ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, verifier, REPORT, current, PATIENT, SPECIMEN, "other-order",
                VERIFIER, OWNER, QUEUE, REASON))).status());

        assertEquals(2, service.revisions(ORG, REPORT).size(),
                "every refusal leaves the revision history unchanged");
        assertEquals(1, service.events(ORG).size());
    }

    /** Negative: a preliminary report cannot be corrected before it is issued. */
    @Test
    void correctBeforeVerificationIsRejected() {
        ReportRevision preliminary = captured();

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.correct(correct(ORG, Set.of(LabRules.VERIFIER),
                        preliminary.version(), REASON))).status());

        assertEquals(VerificationState.PRELIMINARY, service.read(ORG, REPORT).state());
        assertEquals(1, service.revisions(ORG, REPORT).size());
        assertTrue(service.events(ORG).isEmpty());
    }
}
