package com.aurevia.smartclinic.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.lab.result.LabCommands.CaptureCommand;
import com.aurevia.smartclinic.lab.result.LabCommands.VerifyCommand;
import com.aurevia.smartclinic.lab.result.LabObservation;
import com.aurevia.smartclinic.lab.result.LabPolicyCatalog;
import com.aurevia.smartclinic.lab.result.LabPolicyCatalog.CriticalPolicy;
import com.aurevia.smartclinic.lab.result.LabPolicyCatalog.ReferenceRange;
import com.aurevia.smartclinic.lab.result.LabResultService;
import com.aurevia.smartclinic.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.smartclinic.lab.result.LabTypes.ObservationKind;
import com.aurevia.smartclinic.lab.result.LabTypes.ObservationPresence;
import com.aurevia.smartclinic.lab.result.LabTypes.Qualifier;
import com.aurevia.smartclinic.lab.result.LabTypes.TriggerType;
import com.aurevia.smartclinic.lab.result.ReportRevision;

/**
 * LAB-02 clause 4: approved site reference ranges and critical policies are
 * versioned.
 *
 * <p>These tests exercise the domain kernel through its normal command entry
 * points: a collector captures a synthetic observation, a verifier issues it,
 * and the issued revision must carry the newest approved, effective version of
 * the range and the critical policy. The negative tests show that a future,
 * unapproved or expired version is refused and that an empty catalog never
 * invents a threshold. All identifiers, values and policies here are synthetic;
 * no patient data or credential is used.</p>
 *
 * <p>This class proves only the versioning mechanism of the domain kernel. It
 * deliberately does not claim that site policies can be approved or versioned
 * through the HTTP API; that part of the clause is a documented gap.</p>
 */
class ApprovedSiteReferenceRangesCriticalPoliciesVersionedTest {

    private static final String ORG = "org-a";
    private static final String REPORT = "report-a";
    private static final String PATIENT = "patient-a";
    private static final String SPECIMEN = "specimen-a";
    private static final String ORDER = "order-a";
    private static final String COLLECTOR = "synthetic-collector";
    private static final String VERIFIER = "synthetic-verifier";
    private static final String OWNER = "synthetic-owner";
    private static final String QUEUE = "synthetic-coverage-queue";
    private static final String ESCALATION = "synthetic-escalation";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    /**
     * Build one approved, effective-dated reference range for the synthetic GLU test.
     *
     * @param version  catalog version of the range
     * @param low      lower approved bound, inclusive
     * @param high     upper approved bound, exclusive
     * @param approved whether the range has been approved by the site
     * @param from     first effective instant
     * @param to       last effective instant, or {@code null} for open-ended
     * @return the immutable reference range
     */
    private static ReferenceRange range(int version, String low, String high, boolean approved,
                                        Instant from, Instant to) {
        return new ReferenceRange("GLU", "mg/dL", new BigDecimal(low), new BigDecimal(high),
                version, approved, from, to);
    }

    /**
     * Build one approved, effective-dated critical policy for the synthetic GLU test.
     *
     * @param version  catalog version of the policy
     * @param high     value at or above which the result is critical
     * @param approved whether the policy has been approved by the site
     * @param from     first effective instant
     * @param to       last effective instant, or {@code null} for open-ended
     * @return the immutable critical policy
     */
    private static CriticalPolicy critical(int version, String high, boolean approved,
                                           Instant from, Instant to) {
        return new CriticalPolicy("GLU", version, new BigDecimal("20"), new BigDecimal(high),
                approved, from, to, OWNER, QUEUE, Duration.ofMinutes(30), ESCALATION);
    }

    /**
     * Build one present exact numeric observation carrying a caller-supplied flag.
     *
     * @param value the measured synthetic value
     * @param flag  the flag submitted with the observation, before evaluation
     * @return the immutable observation
     */
    private static LabObservation numeric(String value, AbnormalFlag flag) {
        return new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal(value), Qualifier.NONE, "mg/dL", null, null, null, flag,
                "synthetic-analyser", null);
    }

    /**
     * Build a report kernel scoped to the supplied catalog and a fixed evaluation instant.
     *
     * @param catalog approved, effective-dated policies under test
     * @return the report kernel
     */
    private static LabResultService service(LabPolicyCatalog catalog) {
        return new LabResultService(catalog, Clock.fixed(AT, ZoneOffset.UTC));
    }

    /**
     * Capture and issue one report through the domain command entry points.
     *
     * @param service      kernel configured with the catalog under test
     * @param observations observations to capture
     * @return the issued revision, with range and critical versions stamped on it
     */
    private static ReportRevision issue(LabResultService service, List<LabObservation> observations) {
        service.capture(new CaptureCommand(ORG, COLLECTOR, Set.of("lab_collector"), REPORT,
                PATIENT, SPECIMEN, ORDER, observations));
        return service.verify(new VerifyCommand(ORG, VERIFIER, Set.of("lab_verifier"), REPORT,
                1, PATIENT, SPECIMEN, ORDER, VERIFIER, OWNER, QUEUE));
    }

    /**
     * The newest approved effective versions of both policies are applied and
     * stamped on the issued revision: version 2 makes 95 high (range high 90) and
     * critical (policy high 92), where version 1 would have left it normal.
     */
    @Test
    void highestApprovedEffectiveVersionIsSelectedAndStampedOnTheIssuedRevision() {
        LabPolicyCatalog catalog = new LabPolicyCatalog(
                List.of(range(1, "70", "100", true, FROM, TO),
                        range(2, "70", "90", true, FROM, TO)),
                List.of(critical(1, "150", true, FROM, TO),
                        critical(2, "92", true, FROM, TO)));
        LabResultService service = service(catalog);

        ReportRevision issued = issue(service, List.of(numeric("95", AbnormalFlag.NONE)));

        assertEquals(2, issued.version());
        assertEquals(Integer.valueOf(1), issued.supersedesVersion());
        assertEquals(AbnormalFlag.CRITICAL, issued.observations().get(0).abnormalFlag());
        assertEquals(Integer.valueOf(2), issued.rangeVersion());
        assertEquals(Integer.valueOf(2), issued.criticalPolicyVersion());
    }

    /**
     * A higher version that is future-dated, unapproved or already expired is
     * never applied; the newest approved effective version still wins.
     */
    @Test
    void futureDatedAndUnapprovedVersionsAreNeverApplied() {
        LabPolicyCatalog catalog = new LabPolicyCatalog(
                List.of(range(2, "70", "90", true, FROM, TO),
                        range(3, "1", "10", true, AT.plusSeconds(1), TO),
                        range(4, "1", "50", false, FROM, TO),
                        range(5, "1", "20", true, FROM, AT.minusSeconds(1))),
                List.of(critical(2, "92", true, FROM, TO),
                        critical(3, "1000", true, AT.plusSeconds(1), TO),
                        critical(4, "2000", false, FROM, TO),
                        critical(5, "3000", true, FROM, AT.minusSeconds(1))));
        LabResultService service = service(catalog);

        ReportRevision issued = issue(service, List.of(numeric("95", AbnormalFlag.NONE)));

        assertEquals(AbnormalFlag.CRITICAL, issued.observations().get(0).abnormalFlag());
        assertEquals(Integer.valueOf(2), issued.rangeVersion());
        assertEquals(Integer.valueOf(2), issued.criticalPolicyVersion());
    }

    /**
     * An unapproved critical policy never classifies a result: the observation
     * keeps its normal range flag, no critical version is stamped and no XCH-05
     * escalation event is emitted.
     */
    @Test
    void unapprovedCriticalPolicyDoesNotClassifyAResult() {
        LabPolicyCatalog catalog = new LabPolicyCatalog(
                List.of(range(1, "70", "100", true, FROM, TO)),
                List.of(critical(2, "90", false, FROM, TO)));
        LabResultService service = service(catalog);

        ReportRevision issued = issue(service, List.of(numeric("95", AbnormalFlag.NONE)));

        assertEquals(AbnormalFlag.NONE, issued.observations().get(0).abnormalFlag());
        assertEquals(Integer.valueOf(1), issued.rangeVersion());
        assertNull(issued.criticalPolicyVersion());
        assertEquals(1, service.events(ORG).size());
        assertEquals(TriggerType.RESULT_ISSUED, service.events(ORG).get(0).type());
        assertTrue(service.events(ORG).stream()
                .noneMatch(event -> event.type() == TriggerType.CRITICAL_RESULT),
                "an unapproved policy must not emit an XCH-05 escalation");
    }

    /**
     * An empty catalog, like the one wired into the HTTP result-entry service,
     * preserves the submitted flag and invents no version: there is no implicit
     * default range or critical policy.
     */
    @Test
    void emptyCatalogLeavesTheSubmittedFlagAndNullVersion() {
        LabResultService service = service(new LabPolicyCatalog(List.of(), List.of()));

        ReportRevision issued = issue(service, List.of(numeric("95", AbnormalFlag.HIGH)));

        assertEquals(AbnormalFlag.HIGH, issued.observations().get(0).abnormalFlag());
        assertNull(issued.rangeVersion());
        assertNull(issued.criticalPolicyVersion());
        assertEquals(1, service.events(ORG).size(), "a preserved flag is not a critical finding");
    }
}
