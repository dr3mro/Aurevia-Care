package com.aurevia.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Canonical UUID validation through the RX-01 request boundary. */
class PrescriptionInputsUuidTest {

    private static final String CANONICAL = "11111111-2222-3333-4444-555555555555";
    private static final String UPPERCASE = CANONICAL.toUpperCase(Locale.ROOT);

    @Test
    void canonicalUuidIsAcceptedUnchanged() {
        assertEquals(CANONICAL, PrescriptionInputs.uuid(CANONICAL, "patientId"));
    }

    @Test
    void uppercaseUuidIsCanonicalizedToLowercase() {
        assertEquals(CANONICAL, PrescriptionInputs.uuid(UPPERCASE, "patientId"));
    }

    @Test
    void shortFormAndMalformedUuidAreRejectedWithFieldCode() {
        for (String value : new String[] {"1-1-1-1-1", "nope"}) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> PrescriptionInputs.uuid(value, "patientId"));
            assertEquals(422, failure.status());
            assertEquals("INVALID_PATIENTID", failure.code());
        }
    }

    @Test
    void nullUuidIsRejectedWithFieldCode() {
        ApiException failure = assertThrows(ApiException.class,
                () -> PrescriptionInputs.uuid(null, "patientId"));
        assertEquals(422, failure.status());
        assertEquals("INVALID_PATIENTID", failure.code());
    }
}
