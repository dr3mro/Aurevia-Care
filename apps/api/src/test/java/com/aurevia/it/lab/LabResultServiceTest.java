package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabCommands.CaptureCommand;
import com.aurevia.lab.result.LabCommands.CorrectCommand;
import com.aurevia.lab.result.LabPolicyCatalog.CriticalPolicy;
import com.aurevia.lab.result.LabCommands.ErrorCommand;
import com.aurevia.lab.result.LabPolicyCatalog;
import com.aurevia.lab.result.LabResultService;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;
import com.aurevia.lab.result.LabPolicyCatalog.ReferenceRange;
import com.aurevia.lab.result.ReportRevision;
import com.aurevia.lab.result.TriggerEvent;
import com.aurevia.lab.result.LabTypes.TriggerType;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.lab.result.LabCommands.VerifyCommand;

/** LAB-02 service: capture, verification, issued revisions and XCH-04/05 triggers. */
class LabResultServiceTest {

    private static final String ORG = "org-a";
    private static final String OTHER_ORG = "org-b";
    private static final String REPORT = "report-a";
    private static final String PATIENT = "patient-a";
    private static final String SPECIMEN = "specimen-a";
    private static final String ORDER = "order-a";
    private static final String COLLECTOR = "synthetic-collector";
    private static final String VERIFIER = "synthetic-verifier";
    private static final String OWNER = "synthetic-owner";
    private static final String QUEUE = "synthetic-coverage-queue";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private LabPolicyCatalog policies;
    private LabResultService service;

    @BeforeEach
    void setUp() {
        policies = new LabPolicyCatalog(
                List.of(new ReferenceRange("GLU", "mg/dL", new BigDecimal("70"), new BigDecimal("110"),
                        2, true, FROM, null)),
                List.of());
        service = new LabResultService(policies, Clock.fixed(AT, ZoneOffset.UTC));
    }

    private static LabObservation numeric(String code, String value) {
        return new LabObservation(code, ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal(value), Qualifier.NONE, "mg/dL", null, null, null,
                AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    private static CaptureCommand capture(String org, Set<String> roles, List<LabObservation> observations) {
        return new CaptureCommand(org, COLLECTOR, roles, REPORT, PATIENT, SPECIMEN, ORDER, observations);
    }

    private static VerifyCommand verify(String org, Set<String> roles, int version) {
        return new VerifyCommand(org, VERIFIER, roles, REPORT, version, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE);
    }

    private ReportRevision captured() {
        return service.capture(capture(ORG, Set.of("lab_collector"), List.of(numeric("GLU", "90"))));
    }

    private ReportRevision issued() {
        ReportRevision preliminary = captured();
        return service.verify(verify(ORG, Set.of("lab_verifier"), preliminary.version()));
    }

    @Test
    void captureCreatesAPreliminaryRevisionAndDeniesUnauthorizedOrInvalidCommands() {
        ReportRevision revision = captured();
        assertEquals(VerificationState.PRELIMINARY, revision.state());
        assertEquals(1, revision.version());
        assertEquals(ORG, revision.organizationId());
        assertEquals(PATIENT, revision.patientId());
        assertEquals(1, revision.observations().size());
        assertEquals(COLLECTOR, revision.actor());
        assertNull(revision.verifier());
        assertTrue(service.events(ORG).isEmpty());

        ApiException denial = assertThrows(ApiException.class,
                () -> service.capture(capture(ORG, Set.of("lab_verifier"), List.of(numeric("GLU", "90")))));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());

        assertEquals(422, assertThrows(ApiException.class,
                () -> service.capture(capture(" ", Set.of("lab_collector"), List.of(numeric("GLU", "90")))))
                .status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.capture(capture(ORG, Set.of("lab_collector"), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.capture(new CaptureCommand(ORG, " ", Set.of("lab_collector"), REPORT,
                        PATIENT, SPECIMEN, ORDER, List.of(numeric("GLU", "90"))))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.capture(new CaptureCommand(ORG, COLLECTOR, Set.of("lab_collector"), REPORT,
                        " ", SPECIMEN, ORDER, List.of(numeric("GLU", "90"))))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.capture(new CaptureCommand(ORG, COLLECTOR, Set.of("lab_collector"), REPORT,
                        PATIENT, " ", ORDER, List.of(numeric("GLU", "90"))))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.capture(new CaptureCommand(ORG, COLLECTOR, Set.of("lab_collector"), REPORT,
                        PATIENT, SPECIMEN, " ", List.of(numeric("GLU", "90"))))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.capture(new CaptureCommand(ORG, COLLECTOR, Set.of("lab_collector"), " ",
                        PATIENT, SPECIMEN, ORDER, List.of(numeric("GLU", "90"))))).status());

        assertEquals(409, assertThrows(ApiException.class,
                () -> captured()).status());
    }

    @Test
    void verificationRequiresVerifierRoleMatchingIdentifiersAndCurrentVersion() {
        ReportRevision preliminary = captured();

        assertEquals(403, assertThrows(ApiException.class,
                () -> service.verify(verify(ORG, Set.of("lab_collector"), 1))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.verify(verify(OTHER_ORG, Set.of("lab_verifier"), 1))).status());
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.verify(verify(ORG, Set.of("lab_verifier"), 2))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, "other-patient", SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, "other-specimen", ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, "other-order",
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, ORDER,
                " ", OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                " ", VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, " ", Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, ORDER,
                VERIFIER, " ", QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, " "))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), " ", 1, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, " ", SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, " ", ORDER,
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verify(new VerifyCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, " ",
                VERIFIER, OWNER, QUEUE))).status());
        assertEquals(1, preliminary.version(), "a denied verification leaves the revision unchanged");
    }

    @Test
    void finalIssueEvaluatesTheApprovedRangeAndRoutesOneXch04Event() {
        ReportRevision issued = issued();
        assertEquals(VerificationState.FINAL, issued.state());
        assertEquals(2, issued.version());
        assertEquals(Integer.valueOf(1), issued.supersedesVersion());
        assertEquals(VERIFIER, issued.verifier());
        assertEquals(AT, issued.effectiveAt());
        assertEquals(Integer.valueOf(2), issued.rangeVersion());
        assertNull(issued.criticalPolicyVersion());
        assertEquals(AbnormalFlag.NONE, issued.observations().get(0).abnormalFlag());
        assertEquals(VERIFIER, issued.observations().get(0).verifier());

        assertEquals(1, service.events(ORG).size());
        TriggerEvent event = service.events(ORG).get(0);
        assertEquals(TriggerType.RESULT_ISSUED, event.type());
        assertEquals(OWNER, event.owner());
        assertEquals(QUEUE, event.queue());
        assertEquals(PATIENT, event.patientId());
        assertEquals(ORDER, event.orderId());
        assertNull(event.dueAt());
    }

    @Test
    void outOfRangeValuesAreFlaggedFromTheVersionedApprovedRange() {
        service.capture(capture(ORG, Set.of("lab_collector"), List.of(numeric("GLU", "150"))));
        ReportRevision high = service.verify(verify(ORG, Set.of("lab_verifier"), 1));
        assertEquals(VerificationState.FINAL, high.state());
        assertEquals(AbnormalFlag.HIGH, high.observations().get(0).abnormalFlag());
        assertEquals(Integer.valueOf(2), high.rangeVersion());
        assertEquals(1, service.events(ORG).size(), "an abnormal (non-critical) result does not escalate");
        assertEquals(TriggerType.RESULT_ISSUED, service.events(ORG).get(0).type());
    }

    @Test
    void criticalPolicyDrivesOneXch05AlertWithOwnerDueTimeAndEscalation() {
        LabPolicyCatalog criticalPolicies = new LabPolicyCatalog(List.of(), List.of(
                new CriticalPolicy("GLU", 4, new BigDecimal("20"), new BigDecimal("400"), true,
                        FROM, null, "critical-owner", "critical-queue", Duration.ofMinutes(15),
                        "critical-escalation")));
        LabResultService criticalService = new LabResultService(criticalPolicies,
                Clock.fixed(AT, ZoneOffset.UTC));
        criticalService.capture(capture(ORG, Set.of("lab_collector"),
                List.of(numeric("GLU", "450"))));
        ReportRevision issued = criticalService.verify(verify(ORG, Set.of("lab_verifier"), 1));

        assertEquals(AbnormalFlag.CRITICAL, issued.observations().get(0).abnormalFlag());
        assertEquals(Integer.valueOf(4), issued.criticalPolicyVersion());
        assertEquals(2, criticalService.events(ORG).size());
        TriggerEvent alert = criticalService.events(ORG).get(1);
        assertEquals(TriggerType.CRITICAL_RESULT, alert.type());
        assertEquals("critical-owner", alert.owner());
        assertEquals("critical-queue", alert.queue());
        assertEquals("critical-escalation", alert.escalation());
        assertEquals(AT.plus(Duration.ofMinutes(15)), alert.dueAt());
        assertNotEquals(criticalService.events(ORG).get(0).eventId(), alert.eventId());
    }

    @Test
    void qualifiedAndQualitativeObservationsArePreservedButNotEvaluatedOrTrended() {
        LabObservation qualified = new LabObservation("GLU", ObservationKind.QUALIFIED_NUMERIC,
                ObservationPresence.PRESENT, new BigDecimal("5"), Qualifier.LT, "mg/dL", null, null,
                null, AbnormalFlag.NONE, "synthetic-analyser", null);
        LabObservation text = new LabObservation("NOTE", ObservationKind.TEXT, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, "patient reported fasting", null, null,
                AbnormalFlag.NONE, null, null);
        LabResultService serviceWithPolicy = new LabResultService(
                new LabPolicyCatalog(List.of(new ReferenceRange("GLU", "mg/dL", new BigDecimal("70"),
                        new BigDecimal("110"), 2, true, FROM, null)), List.of()),
                Clock.fixed(AT, ZoneOffset.UTC));
        serviceWithPolicy.capture(capture(ORG, Set.of("lab_collector"), List.of(qualified, text)));
        ReportRevision verified = serviceWithPolicy.verify(verify(ORG, Set.of("lab_verifier"), 1));

        assertEquals(AbnormalFlag.NONE, verified.observations().get(0).abnormalFlag());
        assertEquals(VERIFIER, verified.observations().get(0).verifier());
        assertEquals("<5 mg/dL", verified.observations().get(0).display());
        assertEquals(2, verified.observations().size(), "invisible-to-trending values stay visible");
        assertEquals(0, serviceWithPolicy.comparable(ORG, REPORT).size());
    }

    @Test
    void correctionAppendsALinkedRevisionReopensReviewAndNotifiesWithANewEvent() {
        ReportRevision issued = issued();
        ReportRevision corrected = service.correct(new CorrectCommand(ORG, VERIFIER,
                Set.of("lab_verifier"), REPORT, issued.version(), PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "transcription fix"));

        assertEquals(VerificationState.CORRECTED, corrected.state());
        assertEquals(3, corrected.version());
        assertEquals(Integer.valueOf(2), corrected.supersedesVersion());
        assertEquals("transcription fix", corrected.reason());
        assertEquals(3, service.revisions(ORG, REPORT).size());
        assertEquals(VerificationState.FINAL, service.revisions(ORG, REPORT).get(1).state());
        assertEquals(2, service.events(ORG).size());
        assertEquals(TriggerType.RESULT_CORRECTED, service.events(ORG).get(1).type());

        assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(ORG, VERIFIER,
                Set.of("lab_collector"), REPORT, 3, PATIENT, SPECIMEN, ORDER, VERIFIER, OWNER, QUEUE,
                "fix")));
        assertEquals(404, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                OTHER_ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(412, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, " "))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, "other-patient", SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, "other-specimen", ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, "other-order",
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, ORDER,
                " ", OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, ORDER,
                VERIFIER, " ", QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, " ", "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), " ", 3, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, " ", SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, " ", ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, " ",
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                " ", VERIFIER, Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, " ", Set.of("lab_verifier"), REPORT, 3, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
    }

    @Test
    void enteredInErrorPreservesThePriorRevisionIsTerminalAndWithdraws() {
        ReportRevision issued = issued();
        ReportRevision errored = service.enterInError(new ErrorCommand(ORG, VERIFIER,
                Set.of("lab_verifier"), REPORT, issued.version(), "wrong specimen"));

        assertEquals(VerificationState.ENTERED_IN_ERROR, errored.state());
        assertEquals(3, errored.version());
        assertEquals("wrong specimen", errored.reason());
        assertEquals(VerificationState.ENTERED_IN_ERROR, service.read(ORG, REPORT).state());
        assertEquals(VerificationState.FINAL, service.revisions(ORG, REPORT).get(1).state(),
                "the issued revision is preserved");
        assertEquals(2, service.events(ORG).size());
        assertEquals(TriggerType.RESULT_WITHDRAWN, service.events(ORG).get(1).type());
        assertEquals(409, assertThrows(ApiException.class, () -> service.verify(verify(ORG,
                Set.of("lab_verifier"), errored.version()))).status());

        assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(ORG, VERIFIER,
                Set.of("lab_collector"), REPORT, errored.version(), "fix")));
        assertEquals(404, assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(
                OTHER_ORG, VERIFIER, Set.of("lab_verifier"), REPORT, errored.version(), "fix"))).status());
        assertEquals(412, assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, "fix"))).status());
        assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(ORG, VERIFIER,
                Set.of("lab_verifier"), REPORT, errored.version(), " ")));
        assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(" ", VERIFIER,
                Set.of("lab_verifier"), REPORT, errored.version(), "fix")));
        assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(ORG, " ",
                Set.of("lab_verifier"), REPORT, errored.version(), "fix")));
        assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(ORG, VERIFIER,
                Set.of("lab_verifier"), " ", errored.version(), "fix")));
    }

    @Test
    void illegalIssueCorrectionAndWithdrawalTransitionsAreRejected() {
        ReportRevision preliminary = captured();
        assertEquals(409, assertThrows(ApiException.class, () -> service.correct(new CorrectCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 1, PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "fix"))).status());
        // A preliminary revision may be withdrawn, but the terminal revision cannot move again.
        ReportRevision errored = service.enterInError(new ErrorCommand(ORG, VERIFIER,
                Set.of("lab_verifier"), REPORT, 1, "fix"));
        assertEquals(VerificationState.ENTERED_IN_ERROR, errored.state());
        assertEquals(409, assertThrows(ApiException.class, () -> service.enterInError(new ErrorCommand(
                ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 2, "again"))).status());
        assertEquals(1, preliminary.version());
    }

    @Test
    void criticalCorrectionEmitsAFreshCriticalResultForTheNewRevision() {
        LabResultService criticalService = new LabResultService(new LabPolicyCatalog(List.of(), List.of(
                new CriticalPolicy("GLU", 4, new BigDecimal("20"), new BigDecimal("400"), true,
                        FROM, null, "critical-owner", "critical-queue", Duration.ofMinutes(15),
                        "critical-escalation"))), Clock.fixed(AT, ZoneOffset.UTC));
        criticalService.capture(capture(ORG, Set.of("lab_collector"), List.of(numeric("GLU", "450"))));
        criticalService.verify(verify(ORG, Set.of("lab_verifier"), 1));
        criticalService.correct(new CorrectCommand(ORG, VERIFIER, Set.of("lab_verifier"), REPORT, 2,
                PATIENT, SPECIMEN, ORDER, VERIFIER, OWNER, QUEUE, "confirm critical"));

        assertEquals(4, criticalService.events(ORG).size());
        assertEquals(TriggerType.CRITICAL_RESULT, criticalService.events(ORG).get(3).type());
        assertEquals(3, criticalService.events(ORG).get(3).reportVersion());
    }

    @Test
    void approvedCriticalPolicyThatDoesNotFireDoesNotEscalate() {
        LabResultService criticalService = new LabResultService(new LabPolicyCatalog(List.of(), List.of(
                new CriticalPolicy("GLU", 4, new BigDecimal("20"), new BigDecimal("400"), true,
                        FROM, null, "critical-owner", "critical-queue", Duration.ofMinutes(15),
                        "critical-escalation"))), Clock.fixed(AT, ZoneOffset.UTC));
        criticalService.capture(capture(ORG, Set.of("lab_collector"), List.of(numeric("GLU", "90"))));
        criticalService.verify(verify(ORG, Set.of("lab_verifier"), 1));
        assertEquals(1, criticalService.events(ORG).size());
    }

    @Test
    void unknownObservationsAreVerifiedWithoutBeingEvaluatedOrTrended() {
        LabObservation unknown = new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.UNKNOWN,
                null, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);
        service.capture(capture(ORG, Set.of("lab_collector"), List.of(unknown)));
        ReportRevision issued = service.verify(verify(ORG, Set.of("lab_verifier"), 1));
        assertEquals(ObservationPresence.UNKNOWN, issued.observations().get(0).presence());
        assertEquals(VERIFIER, issued.observations().get(0).verifier());
        assertEquals(0, service.comparable(ORG, REPORT).size());
    }

    @Test
    void nullCommandFieldsAreRejectedAsInvalidInput() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.capture(new CaptureCommand(
                null, COLLECTOR, Set.of("lab_collector"), REPORT, PATIENT, SPECIMEN, ORDER,
                List.of(numeric("GLU", "90"))))).status());
    }

    @Test
    void multipleCriticalFindingsProduceOneEscalationEachWithDistinctIdentity() {
        LabResultService panel = new LabResultService(new LabPolicyCatalog(List.of(), List.of(
                new CriticalPolicy("GLU", 4, new BigDecimal("20"), new BigDecimal("400"), true,
                        FROM, null, "glu-owner", "glu-queue", Duration.ofMinutes(15), "glu-escalation"),
                new CriticalPolicy("K", 2, new BigDecimal("1"), new BigDecimal("5"), true,
                        FROM, null, "k-owner", "k-queue", Duration.ofMinutes(5), "k-escalation"))),
                Clock.fixed(AT, ZoneOffset.UTC));
        panel.capture(capture(ORG, Set.of("lab_collector"),
                List.of(numeric("GLU", "450"), numeric("K", "6.5"))));
        panel.verify(verify(ORG, Set.of("lab_verifier"), 1));

        List<TriggerEvent> all = panel.events(ORG);
        assertEquals(3, all.size());
        List<TriggerEvent> alerts = all.stream()
                .filter(event -> event.type() == TriggerType.CRITICAL_RESULT).toList();
        assertEquals(2, alerts.size());
        TriggerEvent glucose = alerts.stream()
                .filter(event -> "GLU".equals(event.findingCode())).findFirst().orElseThrow();
        TriggerEvent potassium = alerts.stream()
                .filter(event -> "K".equals(event.findingCode())).findFirst().orElseThrow();
        assertNotEquals(glucose.eventId(), potassium.eventId());
        assertEquals("glu-owner", glucose.owner());
        assertEquals("glu-queue", glucose.queue());
        assertEquals(AT.plus(Duration.ofMinutes(15)), glucose.dueAt());
        assertEquals("k-owner", potassium.owner());
        assertEquals(AT.plus(Duration.ofMinutes(5)), potassium.dueAt());
        assertEquals("glu-escalation", glucose.escalation());
        assertEquals("k-escalation", potassium.escalation());
    }

    @Test
    void correctionCanReplaceObservedValuesWithoutEditingThePriorRevision() {
        service.capture(capture(ORG, Set.of("lab_collector"), List.of(numeric("GLU", "150"))));
        ReportRevision issued = service.verify(verify(ORG, Set.of("lab_verifier"), 1));
        ReportRevision corrected = service.correct(new CorrectCommand(ORG, VERIFIER,
                Set.of("lab_verifier"), REPORT, issued.version(), PATIENT, SPECIMEN, ORDER,
                VERIFIER, OWNER, QUEUE, "transcription fix"), List.of(numeric("GLU", "90")));

        assertEquals(new BigDecimal("90"), corrected.observations().get(0).value());
        assertEquals(AbnormalFlag.NONE, corrected.observations().get(0).abnormalFlag());
        assertEquals(new BigDecimal("150"), service.revisions(ORG, REPORT).get(1)
                .observations().get(0).value());
        assertEquals(AbnormalFlag.HIGH, service.revisions(ORG, REPORT).get(1)
                .observations().get(0).abnormalFlag());
    }

    @Test
    void eventsAndRevisionsAreIsolatedEvenForDelimiterBearingIdentifiers() {
        service.capture(capture(ORG, Set.of("lab_collector"), List.of(numeric("GLU", "90"))));
        service.verify(verify(ORG, Set.of("lab_verifier"), 1));
        assertEquals(1, service.events(ORG).size());
        assertEquals(0, service.events(OTHER_ORG).size());

        LabResultService other = new LabResultService(policies, Clock.fixed(AT, ZoneOffset.UTC));
        other.capture(capture(OTHER_ORG, Set.of("lab_collector"), List.of(numeric("GLU", "90"))));
        other.verify(verify(OTHER_ORG, Set.of("lab_verifier"), 1));
        assertEquals(1, service.events(ORG).size());
        assertEquals(1, other.events(OTHER_ORG).size());
        assertEquals(0, other.events(ORG).size());

        LabResultService delimiters = new LabResultService(policies, Clock.fixed(AT, ZoneOffset.UTC));
        delimiters.capture(new CaptureCommand("a|b", COLLECTOR, Set.of("lab_collector"), "c",
                PATIENT, SPECIMEN, ORDER, List.of(numeric("GLU", "90"))));
        delimiters.capture(new CaptureCommand("a", COLLECTOR, Set.of("lab_collector"), "b|c",
                PATIENT, SPECIMEN, ORDER, List.of(numeric("GLU", "90"))));
        assertEquals(1, delimiters.revisions("a|b", "c").size());
        assertEquals(1, delimiters.revisions("a", "b|c").size());
    }

    @Test
    void unverifiedSourceFlagsAreNotCarriedIntoTheVerifiedReport() {
        LabObservation qualifiedCritical = new LabObservation("GLU", ObservationKind.QUALIFIED_NUMERIC,
                ObservationPresence.PRESENT, new BigDecimal("2"), Qualifier.LT, "mg/dL", null, null,
                null, AbnormalFlag.CRITICAL, "synthetic-analyser", null);
        service.capture(capture(ORG, Set.of("lab_collector"), List.of(qualifiedCritical)));
        ReportRevision issued = service.verify(verify(ORG, Set.of("lab_verifier"), 1));
        assertEquals(AbnormalFlag.NONE, issued.observations().get(0).abnormalFlag());
        assertEquals(1, service.events(ORG).size(), "an unverified flag must not escalate");

        LabObservation sourceHigh = new LabObservation("GLU", ObservationKind.NUMERIC,
                ObservationPresence.PRESENT, new BigDecimal("150"), Qualifier.NONE, "mg/dL", null,
                null, null, AbnormalFlag.HIGH, "synthetic-analyser", null);
        LabResultService noRange = new LabResultService(
                new LabPolicyCatalog(List.of(), List.of()), Clock.fixed(AT, ZoneOffset.UTC));
        noRange.capture(capture(ORG, Set.of("lab_collector"), List.of(sourceHigh)));
        ReportRevision verified = noRange.verify(verify(ORG, Set.of("lab_verifier"), 1));
        assertEquals(AbnormalFlag.HIGH, verified.observations().get(0).abnormalFlag(),
                "a source flag survives when no approved range was configured");
    }

    @Test
    void eventDigestIsBoundToTheExactIssuedRevision() {
        ReportRevision issued = issued();
        String firstDigest = service.events(ORG).get(0).payloadDigest();
        service.correct(new CorrectCommand(ORG, VERIFIER, Set.of("lab_verifier"), REPORT,
                issued.version(), PATIENT, SPECIMEN, ORDER, VERIFIER, OWNER, QUEUE,
                "transcription fix"), List.of(numeric("GLU", "80")));
        String secondDigest = service.events(ORG).get(1).payloadDigest();
        assertNotEquals(firstDigest, secondDigest);
        assertTrue(firstDigest.contains("GLU"));
    }

    @Test
    void readRevisionsComparableAndEventsAreTenantScopedAndImmutable() {
        ReportRevision preliminary = captured();
        assertEquals(preliminary, service.read(ORG, REPORT));
        assertEquals(404, assertThrows(ApiException.class, () -> service.read(OTHER_ORG, REPORT)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.read(ORG, "other")).status());
        assertThrows(UnsupportedOperationException.class, () -> service.revisions(ORG, REPORT).clear());
        assertThrows(UnsupportedOperationException.class, () -> service.events(ORG).clear());

        ReportRevision issued = service.verify(verify(ORG, Set.of("lab_verifier"), 1));
        assertEquals(1, service.comparable(ORG, REPORT).size());
        assertEquals(issued.observations().get(0), service.comparable(ORG, REPORT).get(0));
        assertThrows(UnsupportedOperationException.class, () -> service.comparable(ORG, REPORT).clear());
    }
}
