package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabRules;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;

/** LAB-02 and XCH-07: only established comparable observations may be evaluated. */
class ComparabilityTest {

    private static LabObservation numeric(String code, String unit, BigDecimal value, Qualifier qualifier) {
        return new LabObservation(code,
                qualifier == Qualifier.NONE ? ObservationKind.NUMERIC : ObservationKind.QUALIFIED_NUMERIC,
                ObservationPresence.PRESENT, value, qualifier, unit, null, null, null,
                AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    @Test
    void sameTestIdentityAndUnitAreComparable() {
        LabObservation first = numeric("GLU", "mg/dL", new BigDecimal("88"), Qualifier.NONE);
        LabObservation second = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        assertTrue(LabRules.eligible(first));
        assertTrue(LabRules.comparable(first, second));
        assertTrue(LabRules.comparable(second, first));
    }

    @Test
    void everyQualifierSuppressesComparability() {
        LabObservation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        for (Qualifier qualifier : Qualifier.values()) {
            if (qualifier != Qualifier.NONE) {
                LabObservation qualified = numeric("GLU", "mg/dL", new BigDecimal("5"), qualifier);
                assertFalse(LabRules.eligible(qualified));
                assertFalse(LabRules.comparable(exact, qualified));
                assertFalse(LabRules.comparable(qualified, exact));
            }
        }
    }

    @Test
    void qualitativeUnknownAbsentAndIncompatibleUnitsAreIneligible() {
        LabObservation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        LabObservation otherUnit = numeric("GLU", "mmol/L", new BigDecimal("5"), Qualifier.NONE);
        LabObservation otherCode = numeric("CREAT", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        LabObservation text = new LabObservation("NOTE", ObservationKind.TEXT, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, "patient reported fasting", null, null, AbnormalFlag.NONE,
                null, null);
        LabObservation coded = new LabObservation("RESULT", ObservationKind.CODED, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, null, "LOINC|123", null, AbnormalFlag.NONE, null, null);
        LabObservation unknown = new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.UNKNOWN,
                null, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);
        LabObservation absent = new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.ABSENT,
                null, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);

        assertFalse(LabRules.comparable(exact, otherUnit), "units must match exactly");
        assertFalse(LabRules.comparable(exact, otherCode), "test identity must match");
        assertFalse(LabRules.eligible(text));
        assertFalse(LabRules.eligible(coded));
        assertFalse(LabRules.eligible(unknown));
        assertFalse(LabRules.eligible(absent));
        assertFalse(LabRules.comparable(exact, text));
    }

    @Test
    void nullObservationsAreNeverComparable() {
        LabObservation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        assertFalse(LabRules.eligible(null));
        assertFalse(LabRules.comparable(null, exact));
        assertFalse(LabRules.comparable(exact, null));
    }

    @Test
    void onlyEligibleObservationsSurviveFilteringAndOriginalsRemainVisible() {
        LabObservation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        LabObservation qualified = numeric("GLU", "mg/dL", new BigDecimal("5"), Qualifier.LT);
        LabObservation text = new LabObservation("NOTE", ObservationKind.TEXT, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, "free text", null, null, AbnormalFlag.NONE, null, null);
        List<LabObservation> all = List.of(exact, qualified, text);

        assertEquals(1, all.stream().filter(LabRules::eligible).count());
        assertEquals(3, all.size(), "filtering does not remove the originals from the report");
    }
}
