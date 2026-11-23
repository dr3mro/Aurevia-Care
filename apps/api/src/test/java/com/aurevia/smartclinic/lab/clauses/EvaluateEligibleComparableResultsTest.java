package com.aurevia.smartclinic.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.lab.result.LabCommands.CaptureCommand;
import com.aurevia.smartclinic.lab.result.LabCommands.VerifyCommand;
import com.aurevia.smartclinic.lab.result.LabConversionCatalog;
import com.aurevia.smartclinic.lab.result.LabObservation;
import com.aurevia.smartclinic.lab.result.LabPolicyCatalog;
import com.aurevia.smartclinic.lab.result.LabPolicyCatalog.ReferenceRange;
import com.aurevia.smartclinic.lab.result.LabResultService;
import com.aurevia.smartclinic.lab.result.LabRules;
import com.aurevia.smartclinic.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.smartclinic.lab.result.LabTypes.ObservationKind;
import com.aurevia.smartclinic.lab.result.LabTypes.ObservationPresence;
import com.aurevia.smartclinic.lab.result.LabTypes.Qualifier;
import com.aurevia.smartclinic.lab.result.LabUnitConversion;
import com.aurevia.smartclinic.lab.result.ReportRevision;

/**
 * LAB-02 clause 5 proof: evaluation and trending consider only eligible comparable
 * results. Exact present numbers of one test identity with matching units (or a
 * documented conversion) are the only values that may be joined or interpreted;
 * qualified numbers, qualitative results, unknown and absent observations stay
 * visible but are never compared or flagged. The kernel's tenant key conceals a
 * peer organization's report. All fixtures are synthetic.
 */
class EvaluateEligibleComparableResultsTest {

    private static final String ORG = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_ORG = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SUBJECT = "synthetic-capture-actor";
    private static final String VERIFIER = "synthetic-verify-actor";
    private static final String OWNER = "synthetic-owner";
    private static final String QUEUE = "synthetic-queue";
    private static final String REPORT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_REPORT = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String SPECIMEN = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String ORDER = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String DOCUMENT = "SYNTHETIC-CONVERSION-TABLE|v1";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Set<String> CAPTURE_ROLES = Set.of(LabRules.COLLECTOR);
    private static final Set<String> VERIFY_ROLES = Set.of(LabRules.VERIFIER);

    /** An exact or qualified number, with the qualifier deciding the kind. */
    private static LabObservation numeric(String code, String unit, String value, Qualifier qualifier) {
        return new LabObservation(code,
                qualifier == Qualifier.NONE ? ObservationKind.NUMERIC : ObservationKind.QUALIFIED_NUMERIC,
                ObservationPresence.PRESENT, new BigDecimal(value), qualifier, unit, null, null, null,
                AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    /** A free-text observation, which is never a comparable measurement. */
    private static LabObservation text(String code, String textValue) {
        return new LabObservation(code, ObservationKind.TEXT, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, textValue, null, null, AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    /** A coded qualitative observation, which is never a comparable measurement. */
    private static LabObservation coded(String code, String codedValue) {
        return new LabObservation(code, ObservationKind.CODED, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, null, codedValue, null, AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    /** An explicitly unknown or absent observation, which carries no measured number. */
    private static LabObservation withPresence(String code, ObservationPresence presence) {
        return new LabObservation(code, ObservationKind.NUMERIC, presence, null, Qualifier.NONE, null,
                null, null, null, AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    /** A synthetic documented conversion for the documented-conversion guard case. */
    private static LabUnitConversion conversion(String testCode, String from, String to, String factor,
                                                int scale, int version) {
        return new LabUnitConversion(testCode, from, to, new BigDecimal(factor), scale, version, DOCUMENT);
    }

    /** A kernel with no approved configuration. */
    private static LabResultService service() {
        return new LabResultService(new LabPolicyCatalog(List.of(), List.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A kernel with an approved range that flags the exact GLU numeric HIGH. */
    private static LabResultService serviceWithFlagPolicy() {
        ReferenceRange range = new ReferenceRange("GLU", "mg/dL", new BigDecimal("70"),
                new BigDecimal("110"), 1, true, NOW.minusSeconds(3600), null);
        return new LabResultService(new LabPolicyCatalog(List.of(range), List.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Capture the supplied observations as the preliminary revision of the synthetic report. */
    private static void capture(LabResultService service, List<LabObservation> observations) {
        service.capture(new CaptureCommand(ORG, SUBJECT, CAPTURE_ROLES, REPORT, PATIENT, SPECIMEN,
                ORDER, observations));
    }

    /** Verify the current preliminary revision as a different authenticated subject. */
    private static ReportRevision verify(LabResultService service, int expectedVersion) {
        return service.verify(new VerifyCommand(ORG, SUBJECT, VERIFY_ROLES, REPORT, expectedVersion,
                PATIENT, SPECIMEN, ORDER, VERIFIER, OWNER, QUEUE));
    }

    @Test
    void comparableReturnsOnlyPresentExactNumericObservations() {
        LabObservation first = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabObservation second = numeric("CREAT", "mg/dL", "1.1", Qualifier.NONE);
        LabResultService service = service();
        capture(service, List.of(first, numeric("GLU", "mg/dL", "150", Qualifier.LT),
                text("NOTE", "patient reported fasting"), coded("FLAG", "POSITIVE"),
                withPresence("UNK", ObservationPresence.UNKNOWN),
                withPresence("NA", ObservationPresence.ABSENT), second));

        assertEquals(List.of(first, second), service.comparable(ORG, REPORT),
                "only present exact numerics survive, in capture order");
    }

    @Test
    void qualifiedNumericResultsAreNeverComparable() {
        LabObservation exact = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabConversionCatalog catalog = new LabConversionCatalog(
                List.of(conversion("GLU", "mg/dL", "mmol/L", "0.0555", 2, 1)));

        assertTrue(LabRules.eligible(exact));
        for (Qualifier qualifier : List.of(Qualifier.LT, Qualifier.GT, Qualifier.LTE, Qualifier.GTE)) {
            LabObservation qualified = numeric("GLU", "mg/dL", "150", qualifier);
            assertFalse(LabRules.eligible(qualified), qualifier + " is not eligible");
            assertFalse(LabRules.comparable(exact, qualified), qualifier + " is not comparable");
            assertFalse(LabRules.comparable(qualified, exact), qualifier + " is not comparable");
            assertFalse(LabRules.comparable(exact, qualified, catalog),
                    "a documented conversion never makes a qualified result comparable");
        }
    }

    @Test
    void textCodedUnknownAndAbsentObservationsAreNeverComparable() {
        LabObservation exact = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        List<LabObservation> ineligible = List.of(
                text("NOTE", "free text"), coded("FLAG", "POSITIVE"),
                withPresence("UNK", ObservationPresence.UNKNOWN),
                withPresence("NA", ObservationPresence.ABSENT));

        for (LabObservation candidate : ineligible) {
            assertFalse(LabRules.eligible(candidate), candidate.code() + " is not eligible");
            assertFalse(LabRules.comparable(exact, candidate), candidate.code() + " is not comparable");
            assertFalse(LabRules.comparable(candidate, exact), candidate.code() + " is not comparable");
        }
    }

    @Test
    void comparableRefusesDifferentTestIdentities() {
        LabObservation glucose = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabObservation anotherGlucose = numeric("GLU", "mg/dL", "92", Qualifier.NONE);
        LabObservation creatinine = numeric("CREAT", "mg/dL", "88", Qualifier.NONE);

        assertTrue(LabRules.comparable(glucose, anotherGlucose), "same test and unit are comparable");
        assertFalse(LabRules.comparable(glucose, creatinine), "a different test identity is never comparable");
        assertFalse(LabRules.comparable(creatinine, glucose), "identity mismatch is symmetric");
    }

    @Test
    void undocumentedUnitMismatchIsNotComparable() {
        LabObservation perDecilitre = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabObservation perLitre = numeric("GLU", "mmol/L", "5", Qualifier.NONE);

        assertFalse(LabRules.comparable(perDecilitre, perLitre));
        assertFalse(LabRules.comparable(perDecilitre, perLitre, LabConversionCatalog.none()));
        assertTrue(LabConversionCatalog.none().find("GLU", "mg/dL", "mmol/L").isEmpty(),
                "no undocumented conversion may be invented");
    }

    @Test
    void otherTenantCannotReadTheComparableResults() {
        LabResultService service = service();
        capture(service, List.of(numeric("GLU", "mg/dL", "88", Qualifier.NONE)));
        LabObservation peerObservation = numeric("CREAT", "mg/dL", "1.1", Qualifier.NONE);
        service.capture(new CaptureCommand(OTHER_ORG, SUBJECT, CAPTURE_ROLES, OTHER_REPORT,
                PATIENT, SPECIMEN, ORDER, List.of(peerObservation)));
        assertEquals(1, service.comparable(ORG, REPORT).size(), "the owner tenant can read its result");
        assertEquals(List.of(peerObservation), service.comparable(OTHER_ORG, OTHER_REPORT),
                "the peer tenant sees only its own comparable result");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.comparable(OTHER_ORG, REPORT));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
    }

    @Test
    void ineligibleObservationsReceiveNoInterpretationOnVerify() {
        LabResultService service = serviceWithFlagPolicy();
        capture(service, List.of(
                numeric("GLU", "mg/dL", "150", Qualifier.NONE),
                numeric("GLU", "mg/dL", "150", Qualifier.LT),
                text("NOTE", "patient reported fasting"), coded("FLAG", "POSITIVE"),
                withPresence("UNK", ObservationPresence.UNKNOWN),
                withPresence("NA", ObservationPresence.ABSENT)));

        ReportRevision issued = verify(service, 1);
        List<LabObservation> evaluated = issued.observations();

        assertEquals(6, evaluated.size());
        assertEquals(AbnormalFlag.HIGH, evaluated.get(0).abnormalFlag(),
                "the exact numeric is interpreted against the approved range");
        for (int index = 1; index < evaluated.size(); index++) {
            assertEquals(AbnormalFlag.NONE, evaluated.get(index).abnormalFlag(),
                    evaluated.get(index).code() + " is not interpreted without an exact value");
        }
        for (LabObservation observation : evaluated) {
            assertEquals(VERIFIER, observation.verifier(),
                    "verification evidence is recorded even when no interpretation is derived");
        }
        assertEquals(List.of(evaluated.get(0)), service.comparable(ORG, REPORT),
                "only the exact numeric remains comparable after verification");
    }
}
