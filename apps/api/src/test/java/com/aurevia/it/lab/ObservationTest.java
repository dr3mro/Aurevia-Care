package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;

/** LAB-02: observation data model — four kinds, presence states and metadata. */
class ObservationTest {

    private static LabObservation create(ObservationKind kind, ObservationPresence presence, BigDecimal value,
                                      Qualifier qualifier, String unit, String text, String coded,
                                      String method, AbnormalFlag flag, String instrument, String verifier) {
        return new LabObservation("GLU", kind, presence, value, qualifier, unit, text, coded,
                method, flag, instrument, verifier);
    }

    private static LabObservation numeric(BigDecimal value, Qualifier qualifier, String unit, String method) {
        return create(ObservationKind.NUMERIC, ObservationPresence.PRESENT, value, qualifier, unit,
                null, null, method, AbnormalFlag.NONE, "analyser-1", null);
    }

    @Test
    void numericObservationRetainsValueUnitSourceAndOptionalMethod() {
        LabObservation observation = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", null);
        assertEquals(ObservationKind.NUMERIC, observation.kind());
        assertEquals(ObservationPresence.PRESENT, observation.presence());
        assertEquals(new BigDecimal("90"), observation.value());
        assertEquals("mg/dL", observation.unit());
        assertEquals("analyser-1", observation.instrument());
        assertNull(observation.method());
        assertNull(observation.verifier());

        LabObservation withMethod = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", "hexokinase");
        assertEquals("hexokinase", withMethod.method());
        assertEquals("90 mg/dL (NONE)", withMethod.display());
    }

    @Test
    void qualifiedNumericKeepsTheOperatorAndIsNeverAnExactMeasurement() {
        LabObservation lessThan = create(ObservationKind.QUALIFIED_NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal("5"), Qualifier.LT, "mg/L", null, null, null, AbnormalFlag.NONE, "lab-a", null);
        assertEquals(Qualifier.LT, lessThan.qualifier());
        assertEquals("<5 mg/L", lessThan.display());
        assertNotEquals(new BigDecimal("5"), lessThan.exactValueOrNull());

        for (Qualifier qualifier : Qualifier.values()) {
            if (qualifier != Qualifier.NONE) {
                LabObservation qualified = create(ObservationKind.QUALIFIED_NUMERIC,
                        ObservationPresence.PRESENT, new BigDecimal("2"), qualifier, "mg/L", null, null,
                        null, AbnormalFlag.NONE, "lab-a", null);
                assertTrue(qualified.display().startsWith(qualifier.symbol()));
            }
        }
    }

    @Test
    void textAndCodedObservationsKeepQualitativeValues() {
        LabObservation emptyText = create(ObservationKind.TEXT, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, "", null, null, AbnormalFlag.NONE, null, null);
        assertEquals("", emptyText.textValue());
        assertEquals("(empty text)", emptyText.display());

        LabObservation coded = create(ObservationKind.CODED, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, null, "SNOMED|123", null, AbnormalFlag.NONE, null, null);
        assertEquals("SNOMED|123", coded.codedValue());
        assertEquals("SNOMED|123", coded.display());
    }

    @Test
    void unknownAbsentAndTrueZeroAreDistinctRepresentations() {
        LabObservation zero = numeric(BigDecimal.ZERO, Qualifier.NONE, "mg/dL", null);
        LabObservation unknown = create(ObservationKind.NUMERIC, ObservationPresence.UNKNOWN, null,
                Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);
        LabObservation absent = create(ObservationKind.NUMERIC, ObservationPresence.ABSENT, null,
                Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);

        assertEquals(ObservationPresence.PRESENT, zero.presence());
        assertEquals(BigDecimal.ZERO, zero.value());
        assertEquals(ObservationPresence.UNKNOWN, unknown.presence());
        assertEquals(ObservationPresence.ABSENT, absent.presence());
        assertNull(unknown.value());
        assertNull(absent.value());
        assertNotEquals(zero.display(), unknown.display());
        assertNotEquals(unknown.display(), absent.display());
        assertEquals("unknown", unknown.display());
        assertEquals("absent", absent.display());
    }

    @Test
    void verifiedReturnsANewObservationKeepingTheOriginalImmutable() {
        LabObservation original = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", "hexokinase");
        LabObservation verified = original.verified("synthetic-verifier", AbnormalFlag.HIGH);
        assertEquals("synthetic-verifier", verified.verifier());
        assertEquals(AbnormalFlag.HIGH, verified.abnormalFlag());
        assertNull(original.verifier());
        assertEquals(AbnormalFlag.NONE, original.abnormalFlag());
    }

    @Test
    void rejectsInvalidObservationCombinations() {
        assertThrows(ApiException.class, () -> new LabObservation(null, ObservationKind.NUMERIC,
                ObservationPresence.PRESENT, BigDecimal.ONE, Qualifier.NONE, "mg/dL", null, null,
                null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                BigDecimal.ONE, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.TEXT, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.CODED, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.CODED, ObservationPresence.PRESENT,
                BigDecimal.ONE, Qualifier.NONE, null, null, "SNOMED|123", null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(null, ObservationPresence.PRESENT,
                BigDecimal.ONE, Qualifier.NONE, "mg/dL", null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> new LabObservation(" ", ObservationKind.NUMERIC,
                ObservationPresence.PRESENT, BigDecimal.ONE, Qualifier.NONE, "mg/dL", null, null,
                null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, null,
                BigDecimal.ONE, Qualifier.NONE, "mg/dL", null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                null, Qualifier.NONE, "mg/dL", null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                BigDecimal.ONE, Qualifier.NONE, " ", null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.QUALIFIED_NUMERIC,
                ObservationPresence.PRESENT, BigDecimal.ONE, Qualifier.NONE, "mg/dL", null, null,
                null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                BigDecimal.ONE, Qualifier.LT, "mg/dL", null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.TEXT, ObservationPresence.PRESENT,
                BigDecimal.ONE, Qualifier.NONE, null, "text", null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.CODED, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, null, " ", null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, ObservationPresence.UNKNOWN,
                BigDecimal.ONE, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                BigDecimal.ONE, null, "mg/dL", null, null, null, AbnormalFlag.NONE, null, null));
        assertThrows(ApiException.class, () -> create(ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                BigDecimal.ONE, Qualifier.NONE, "mg/dL", null, null, null, null, null, null));
    }

    @Test
    void valueSemanticsAreStableForSyntheticRecords() {
        LabObservation first = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", null);
        LabObservation second = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", null);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.toString().contains("GLU"));
        assertEquals("numeric", ObservationKind.NUMERIC.wire());
        assertEquals("qualified_numeric", ObservationKind.QUALIFIED_NUMERIC.wire());
        assertEquals("text", ObservationKind.TEXT.wire());
        assertEquals("coded", ObservationKind.CODED.wire());
        assertEquals("present", ObservationPresence.PRESENT.wire());
        assertEquals("unknown", ObservationPresence.UNKNOWN.wire());
        assertEquals("absent", ObservationPresence.ABSENT.wire());
        assertEquals("none", Qualifier.NONE.wire());
        assertEquals("", Qualifier.NONE.symbol());
        assertEquals("none", AbnormalFlag.NONE.wire());

        LabObservation unknown = create(ObservationKind.NUMERIC, ObservationPresence.UNKNOWN, null,
                Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);
        LabObservation text = create(ObservationKind.TEXT, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, "reported normal", null, null, AbnormalFlag.NONE, null, null);
        assertNull(unknown.exactValueOrNull());
        assertNull(text.exactValueOrNull());
        assertEquals("reported normal", text.display());
        assertEquals(new BigDecimal("90"), first.exactValueOrNull());
    }
}
