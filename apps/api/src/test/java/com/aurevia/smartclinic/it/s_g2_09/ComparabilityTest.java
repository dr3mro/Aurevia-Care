package com.aurevia.smartclinic.it.s_g2_09;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.AbnormalFlag;
import com.aurevia.smartclinic.g2.s_g2_09.LabRules;
import com.aurevia.smartclinic.g2.s_g2_09.Observation;
import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.ObservationKind;
import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.ObservationPresence;
import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.Qualifier;

/** LAB-02 and XCH-07: only established comparable observations may be evaluated. */
class ComparabilityTest {

    private static Observation numeric(String code, String unit, BigDecimal value, Qualifier qualifier) {
        return new Observation(code,
                qualifier == Qualifier.NONE ? ObservationKind.NUMERIC : ObservationKind.QUALIFIED_NUMERIC,
                ObservationPresence.PRESENT, value, qualifier, unit, null, null, null,
                AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    @Test
    void sameTestIdentityAndUnitAreComparable() {
        Observation first = numeric("GLU", "mg/dL", new BigDecimal("88"), Qualifier.NONE);
        Observation second = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        assertTrue(LabRules.eligible(first));
        assertTrue(LabRules.comparable(first, second));
        assertTrue(LabRules.comparable(second, first));
    }

    @Test
    void everyQualifierSuppressesComparability() {
        Observation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        for (Qualifier qualifier : Qualifier.values()) {
            if (qualifier != Qualifier.NONE) {
                Observation qualified = numeric("GLU", "mg/dL", new BigDecimal("5"), qualifier);
                assertFalse(LabRules.eligible(qualified));
                assertFalse(LabRules.comparable(exact, qualified));
                assertFalse(LabRules.comparable(qualified, exact));
            }
        }
    }

    @Test
    void qualitativeUnknownAbsentAndIncompatibleUnitsAreIneligible() {
        Observation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        Observation otherUnit = numeric("GLU", "mmol/L", new BigDecimal("5"), Qualifier.NONE);
        Observation otherCode = numeric("CREAT", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        Observation text = new Observation("NOTE", ObservationKind.TEXT, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, "patient reported fasting", null, null, AbnormalFlag.NONE,
                null, null);
        Observation coded = new Observation("RESULT", ObservationKind.CODED, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, null, "LOINC|123", null, AbnormalFlag.NONE, null, null);
        Observation unknown = new Observation("GLU", ObservationKind.NUMERIC, ObservationPresence.UNKNOWN,
                null, Qualifier.NONE, null, null, null, null, AbnormalFlag.NONE, null, null);
        Observation absent = new Observation("GLU", ObservationKind.NUMERIC, ObservationPresence.ABSENT,
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
        Observation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        assertFalse(LabRules.eligible(null));
        assertFalse(LabRules.comparable(null, exact));
        assertFalse(LabRules.comparable(exact, null));
    }

    @Test
    void onlyEligibleObservationsSurviveFilteringAndOriginalsRemainVisible() {
        Observation exact = numeric("GLU", "mg/dL", new BigDecimal("92"), Qualifier.NONE);
        Observation qualified = numeric("GLU", "mg/dL", new BigDecimal("5"), Qualifier.LT);
        Observation text = new Observation("NOTE", ObservationKind.TEXT, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, "free text", null, null, AbnormalFlag.NONE, null, null);
        List<Observation> all = List.of(exact, qualified, text);

        assertEquals(1, all.stream().filter(LabRules::eligible).count());
        assertEquals(3, all.size(), "filtering does not remove the originals from the report");
    }
}
