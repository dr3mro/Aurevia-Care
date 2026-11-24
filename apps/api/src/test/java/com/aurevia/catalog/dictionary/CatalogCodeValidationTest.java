package com.aurevia.catalog.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.contract.CatalogCode;

/**
 * CLN-03: the single catalog-code rule applied at the dictionary trust boundary.
 *
 * <p>Every caller asserts the same valid, invalid, boundary-length and non-ASCII
 * cases so the three validation sites cannot drift apart. The shared primitive
 * is exercised through its public API here as well, because the catalog test
 * package is the owner of these acceptance scenarios.</p>
 */
class CatalogCodeValidationTest {

    private LocalizedText label() {
        return new LocalizedText("Label", "en");
    }

    @Test
    void sharedPrimitiveValidatesAndExposesTheTypedValue() {
        CatalogCode code = CatalogCode.code("R51");
        assertEquals("R51", code.value());
        assertEquals(code, CatalogCode.code("R51"));
        assertEquals(code.hashCode(), CatalogCode.code("R51").hashCode());
        assertEquals("CatalogCode[value=R51]", code.toString());
    }

    @Test
    void sharedPrimitiveAcceptsBoundaryAndRejectsInvalidBoundaryAndArabicInputs() {
        assertEquals("R51_.:-", CatalogCode.code("R51_.:-").value());
        assertEquals("a".repeat(64), CatalogCode.code("a".repeat(64)).value());
        assertThrows(IllegalArgumentException.class, () -> CatalogCode.code(null));
        assertThrows(IllegalArgumentException.class, () -> CatalogCode.code(""));
        assertThrows(IllegalArgumentException.class, () -> CatalogCode.code("a"));
        assertThrows(IllegalArgumentException.class, () -> CatalogCode.code("a".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> CatalogCode.code("Bad Code"));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> CatalogCode.code("رمز"));
        assertEquals(CatalogCode.INVALID_MESSAGE, rejected.getMessage());
    }

    @Test
    void diagnosisCodeAcceptsValidAndMaxLengthCodes() {
        assertEquals("R51", new DiagnosisCode("R51", label(), null, null).code());
        String max = "a".repeat(64);
        assertEquals(max, new DiagnosisCode(max, label(), null, null).code());
        assertEquals("G43.9", new DiagnosisCode("R51", label(), null, null)
                .withMapping("G43.9", label()).mappedCode());
    }

    @Test
    void diagnosisCodeRejectsInvalidAndArabicCodes() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosisCode(null, label(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosisCode("Bad Code", label(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosisCode("a".repeat(65), label(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosisCode("رمز", label(), null, null));
    }

    @Test
    void dictionaryFieldAcceptsValidAndMaxLengthCodes() {
        assertEquals("chief_concern", new DictionaryField("chief_concern", label(),
                Requiredness.REQUIRED, List.of()).code());
        String max = "a".repeat(64);
        assertEquals(max, new DictionaryField(max, label(),
                Requiredness.OPTIONAL, List.of()).code());
    }

    @Test
    void dictionaryFieldRejectsInvalidAndArabicCodes() {
        assertThrows(IllegalArgumentException.class,
                () -> new DictionaryField(null, label(), Requiredness.REQUIRED, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DictionaryField("Bad Code", label(), Requiredness.REQUIRED, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DictionaryField("a".repeat(65), label(), Requiredness.REQUIRED, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DictionaryField("رمز", label(), Requiredness.REQUIRED, List.of()));
    }
}
