package com.aurevia.prescription.rx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Canonical UUID validation through the RX-02 request boundary. */
class RxInputsUuidTest {

    private static final String CANONICAL = "11111111-2222-3333-4444-555555555555";
    private static final String UPPERCASE = CANONICAL.toUpperCase(Locale.ROOT);

    @Test
    void canonicalUuidIsAcceptedUnchanged() {
        assertEquals(CANONICAL, RxInputs.uuid(CANONICAL, "patientId"));
    }

    @Test
    void uppercaseUuidIsCanonicalizedToLowercase() {
        assertEquals(CANONICAL, RxInputs.uuid(UPPERCASE, "patientId"));
    }

    @Test
    void shortFormAndMalformedUuidAreRejectedWithFieldCode() {
        for (String value : new String[] {"1-1-1-1-1", "nope"}) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> RxInputs.uuid(value, "patientId"));
            assertEquals(422, failure.status());
            assertEquals("INVALID_PATIENTID", failure.code());
        }
    }

    @Test
    void nullUuidIsRejectedWithFieldCode() {
        ApiException failure = assertThrows(ApiException.class,
                () -> RxInputs.uuid(null, "patientId"));
        assertEquals(422, failure.status());
        assertEquals("INVALID_PATIENTID", failure.code());
    }
}
