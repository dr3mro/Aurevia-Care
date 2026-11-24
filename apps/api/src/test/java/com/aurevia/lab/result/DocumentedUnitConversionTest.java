package com.aurevia.lab.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;

/**
 * XCH-07: numeric results are joined only when their test identity and unit
 * match, or a documented, versioned conversion is established. Qualifiers stay
 * qualifiers and mixed units without a documented conversion stay visible but
 * unjoined. All values and documents here are synthetic.
 */
class DocumentedUnitConversionTest {

    private static final String DOCUMENT = "SYNTHETIC-CONVERSION-TABLE|v1";

    private static LabObservation numeric(String code, String unit, String value, Qualifier qualifier) {
        return new LabObservation(code,
                qualifier == Qualifier.NONE ? ObservationKind.NUMERIC : ObservationKind.QUALIFIED_NUMERIC,
                ObservationPresence.PRESENT, new BigDecimal(value), qualifier, unit, null, null, null,
                AbnormalFlag.NONE, "synthetic-analyser", null);
    }

    private static LabUnitConversion conversion(String testCode, String from, String to, String factor,
                                                int scale, int version) {
        return new LabUnitConversion(testCode, from, to, new BigDecimal(factor), scale, version, DOCUMENT);
    }

    @Test
    void exactUnitMatchRemainsComparableWithoutAnyConversion() {
        LabObservation first = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabObservation second = numeric("GLU", "mg/dL", "92", Qualifier.NONE);

        assertTrue(LabRules.comparable(first, second));
        assertTrue(LabRules.comparable(first, second, LabConversionCatalog.none()));
    }

    @Test
    void mixedUnitsWithoutADocumentedConversionAreNotJoined() {
        LabObservation perDecilitre = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabObservation perLitre = numeric("GLU", "mmol/L", "5", Qualifier.NONE);

        assertFalse(LabRules.comparable(perDecilitre, perLitre));
        assertFalse(LabRules.comparable(perDecilitre, perLitre, LabConversionCatalog.none()));
        assertTrue(LabConversionCatalog.none().find("GLU", "mg/dL", "mmol/L").isEmpty());
    }

    @Test
    void aDocumentedConversionJoinsMixedUnitsInEitherDirection() {
        LabObservation perDecilitre = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabObservation perLitre = numeric("GLU", "mmol/L", "4880", Qualifier.NONE);
        LabConversionCatalog catalog = new LabConversionCatalog(
                List.of(conversion("GLU", "mg/dL", "mmol/L", "0.0555", 2, 1)));

        assertTrue(LabRules.comparable(perDecilitre, perLitre, catalog));
        assertTrue(LabRules.comparable(perLitre, perDecilitre, catalog));
    }

    @Test
    void aDocumentedConversionNeverJoinsADifferentTestOrAQualifiedValue() {
        LabUnitConversion glucose = conversion("GLU", "mg/dL", "mmol/L", "0.0555", 2, 1);
        LabConversionCatalog catalog = new LabConversionCatalog(List.of(glucose));
        LabObservation exact = numeric("GLU", "mg/dL", "88", Qualifier.NONE);
        LabObservation qualified = numeric("GLU", "mmol/L", "5", Qualifier.LT);
        LabObservation otherTest = numeric("CREAT", "mmol/L", "88", Qualifier.NONE);

        assertFalse(LabRules.comparable(exact, qualified, catalog), "a qualifier is never an exact point");
        assertFalse(LabRules.comparable(exact, otherTest, catalog), "test identity must still match");
    }

    @Test
    void conversionKeepsTheDocumentedScaleAndUsesHalfUpRounding() {
        LabUnitConversion conversion = conversion("GLU", "mg/dL", "mmol/L", "0.0555", 2, 1);

        assertEquals(new BigDecimal("4.88"), conversion.convert(new BigDecimal("88")));
        assertEquals(DOCUMENT, conversion.documentation());
        assertEquals("mg/dL", conversion.fromUnit());
        assertEquals("mmol/L", conversion.toUnit());
        assertEquals("GLU", conversion.testCode());
    }

    @Test
    void catalogSelectsTheHighestApprovedVersionAndOnlyTheExactDirection() {
        LabUnitConversion older = conversion("GLU", "mg/dL", "mmol/L", "0.0555", 2, 1);
        LabUnitConversion newer = conversion("GLU", "mg/dL", "mmol/L", "0.056", 2, 3);
        LabConversionCatalog catalog = new LabConversionCatalog(List.of(older, newer));

        assertEquals(3, catalog.find("GLU", "mg/dL", "mmol/L").orElseThrow().version());
        assertTrue(catalog.find("GLU", "mmol/L", "mg/dL").isEmpty(), "a one-way document is not reversible");
        assertTrue(catalog.find("CREAT", "mg/dL", "mmol/L").isEmpty());
    }

    @Test
    void conversionValidationRejectsIncompleteOrUnsoundedDocuments() {
        assertThrows(ApiException.class, () -> conversion(null, "a", "b", "1", 0, 1));
        assertThrows(ApiException.class, () -> conversion(" ", "a", "b", "1", 0, 1));
        assertThrows(ApiException.class, () -> conversion("GLU", null, "b", "1", 0, 1));
        assertThrows(ApiException.class, () -> conversion("GLU", " ", "b", "1", 0, 1));
        assertThrows(ApiException.class, () -> conversion("GLU", "a", null, "1", 0, 1));
        assertThrows(ApiException.class, () -> conversion("GLU", "a", " ", "1", 0, 1));
        assertThrows(ApiException.class, () -> new LabUnitConversion(
                "GLU", "a", "b", null, 0, 1, DOCUMENT));
        assertThrows(ApiException.class, () -> conversion("GLU", "a", "b", "0", 0, 1));
        assertThrows(ApiException.class, () -> conversion("GLU", "a", "b", "-1", 0, 1));
        assertThrows(ApiException.class, () -> conversion("GLU", "a", "b", "1", -1, 1));
        assertThrows(ApiException.class, () -> conversion("GLU", "a", "b", "1", 0, 0));
        assertThrows(ApiException.class, () -> conversion("GLU", "a", "a", "1", 0, 1));
        assertThrows(ApiException.class, () -> new LabUnitConversion(
                "GLU", "a", "b", new BigDecimal("1"), 0, 1, "  "));
        assertThrows(ApiException.class, () -> new LabUnitConversion(
                "GLU", "a", "b", new BigDecimal("1"), 0, 1, null));
    }

    @Test
    void catalogValidationAndAppliesToCoverTheirBoundaries() {
        assertThrows(ApiException.class, () -> new LabConversionCatalog(null));
        LabUnitConversion conversion = conversion("GLU", "a", "b", "1", 0, 1);
        assertTrue(conversion.appliesTo("GLU", "a", "b"));
        assertFalse(conversion.appliesTo("CREAT", "a", "b"));
        assertFalse(conversion.appliesTo("GLU", "x", "b"));
        assertFalse(conversion.appliesTo("GLU", "a", "x"));
    }
}
