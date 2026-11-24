package com.aurevia.api.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The single facility-code rule applied at every API trust boundary (ARC-02, ARC-03).
 *
 * <p>Every caller asserts the same valid, invalid, boundary-length and Arabic
 * cases so the five validation sites cannot drift apart. The shared primitive is
 * exercised through its public API here as the owner of the rule itself.</p>
 */
class FacilityCodeTest {

    @Test
    void exposesTheTypedValueAndStableIdentity() {
        FacilityCode code = FacilityCode.facilityCode("MAIN");
        assertEquals("MAIN", code.value());
        assertEquals(code, FacilityCode.facilityCode("MAIN"));
        assertEquals(code.hashCode(), FacilityCode.facilityCode("MAIN").hashCode());
        assertEquals("FacilityCode[value=MAIN]", code.toString());
    }

    @Test
    void acceptsUppercaseLettersDigitsHyphenAndUnderscoreAtMaxLength() {
        assertEquals("R51_A-9", FacilityCode.facilityCode("R51_A-9").value());
        assertEquals("A".repeat(32), FacilityCode.facilityCode("A".repeat(32)).value());
    }

    @Test
    void rejectsNullEmptyShortLowercaseAndPunctuation() {
        assertEquals(FacilityCode.INVALID_MESSAGE,
                assertThrows(IllegalArgumentException.class,
                        () -> FacilityCode.facilityCode(null)).getMessage());
        for (String value : List.of("", "A", "main", "Bad Code", "A".repeat(33))) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> FacilityCode.facilityCode(value));
            assertEquals(FacilityCode.INVALID_MESSAGE, rejected.getMessage());
        }
    }

    @Test
    void rejectsArabicRightToLeftScripts() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> FacilityCode.facilityCode("رمز"));
        assertEquals(FacilityCode.INVALID_MESSAGE, rejected.getMessage());
    }

    @Test
    void exposesTheSharedRegexFragmentForCompoundTokens() {
        assertEquals("[A-Z0-9_-]{2,32}", FacilityCode.PATTERN);
    }
}
