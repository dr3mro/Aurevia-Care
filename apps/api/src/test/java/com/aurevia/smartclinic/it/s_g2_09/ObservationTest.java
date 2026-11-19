package com.aurevia.smartclinic.it.s_g2_09;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.AbnormalFlag;
import com.aurevia.smartclinic.g2.s_g2_09.Observation;
import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.ObservationKind;
import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.ObservationPresence;
import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.Qualifier;

/** LAB-02: observation data model — four kinds, presence states and metadata. */
class ObservationTest {

    private static Observation create(ObservationKind kind, ObservationPresence presence, BigDecimal value,
                                      Qualifier qualifier, String unit, String text, String coded,
                                      String method, AbnormalFlag flag, String instrument, String verifier) {
        return new Observation("GLU", kind, presence, value, qualifier, unit, text, coded,
                method, flag, instrument, verifier);
    }

    private static Observation numeric(BigDecimal value, Qualifier qualifier, String unit, String method) {
        return create(ObservationKind.NUMERIC, ObservationPresence.PRESENT, value, qualifier, unit,
                null, null, method, AbnormalFlag.NONE, "analyser-1", null);
    }

    @Test
    void numericObservationRetainsValueUnitSourceAndOptionalMethod() {
        Observation observation = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", null);
        assertEquals(ObservationKind.NUMERIC, observation.kind());
        assertEquals(ObservationPresence.PRESENT, observation.presence());
        assertEquals(new BigDecimal("90"), observation.value());
        assertEquals("mg/dL", observation.unit());
        assertEquals("analyser-1", observation.instrument());
        assertNull(observation.method());
        assertNull(observation.verifier());

        Observation withMethod = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", "hexokinase");
        assertEquals("hexokinase", withMethod.method());
        assertEquals("90 mg/dL (NONE)", withMethod.display());
    }

    @Test
    void qualifiedNumericKeepsTheOperatorAndIsNeverAnExactMeasurement() {
        Observation lessThan = create(ObservationKind.QUALIFIED_NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal("5"), Qualifier.LT, "mg/L", null, null, null, AbnormalFlag.NONE, "lab-a", null);
        assertEquals(Qualifier.LT, lessThan.qualifier());
        assertEquals("<5 mg/L", lessThan.display());
        assertNotEquals(new BigDecimal("5"), lessThan.exactValueOrNull());

        for (Qualifier qualifier : Qualifier.values()) {
            if (qualifier != Qualifier.NONE) {
                Observation qualified = create(ObservationKind.QUALIFIED_NUMERIC,
                        ObservationPresence.PRESENT, new BigDecimal("2"), qualifier, "mg/L", null, null,
                        null, AbnormalFlag.NONE, "lab-a", null);
                assertTrue(qualified.display().startsWith(qualifier.symbol()));
            }
        }
    }

    @Test
    void textAndCodedObservationsKeepQualitativeValues() {
        Observation emptyText = create(ObservationKind.TEXT, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, "", null, null, AbnormalFlag.NONE, null, null);
        assertEquals("", emptyText.textValue());
        assertEquals("(empty text)", emptyText.display());

        Observation coded = create(ObservationKind.CODED, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, null, "SNOMED|123", null, AbnormalFlag.NONE, null, null);
        assertEquals("SNOMED|123", coded.codedValue());
        assertEquals("SNOMED|123", coded.display());
    }

    @Test
    void unknownAbsentAndTrueZeroAreDistinctRepresentations() {
        Observation zero = numeric(BigDecimal.ZERO, Qualifier.NONE, "mg/dL", null);
        Observation unknown = create(ObservationKind.NUMERIC, ObservationPresence.UNKNOWN, null,
                Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);
        Observation absent = create(ObservationKind.NUMERIC, ObservationPresence.ABSENT, null,
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
        Observation original = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", "hexokinase");
        Observation verified = original.verified("synthetic-verifier", AbnormalFlag.HIGH);
        assertEquals("synthetic-verifier", verified.verifier());
        assertEquals(AbnormalFlag.HIGH, verified.abnormalFlag());
        assertNull(original.verifier());
        assertEquals(AbnormalFlag.NONE, original.abnormalFlag());
    }

    @Test
    void rejectsInvalidObservationCombinations() {
        assertThrows(ApiException.class, () -> new Observation(null, ObservationKind.NUMERIC,
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
        assertThrows(ApiException.class, () -> new Observation(" ", ObservationKind.NUMERIC,
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
        Observation first = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", null);
        Observation second = numeric(new BigDecimal("90"), Qualifier.NONE, "mg/dL", null);
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

        Observation unknown = create(ObservationKind.NUMERIC, ObservationPresence.UNKNOWN, null,
                Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);
        Observation text = create(ObservationKind.TEXT, ObservationPresence.PRESENT, null,
                Qualifier.NONE, null, "reported normal", null, null, AbnormalFlag.NONE, null, null);
        assertNull(unknown.exactValueOrNull());
        assertNull(text.exactValueOrNull());
        assertEquals("reported normal", text.display());
        assertEquals(new BigDecimal("90"), first.exactValueOrNull());
    }
}
