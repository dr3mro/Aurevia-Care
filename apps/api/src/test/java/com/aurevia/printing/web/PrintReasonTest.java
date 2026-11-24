package com.aurevia.printing.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * The short, closed print reason list is validated at the HTTP boundary (OPS-02).
 *
 * <p>Synthetic inputs only. A missing, blank or unknown reason is refused before
 * any handout is produced, so a disclosure is never logged without a why.</p>
 */
class PrintReasonTest {

    /** Every approved reason has a stable lowercase wire key. */
    @Test
    void approvedReasonsHaveStableWireKeys() {
        assertEquals("give_to_patient", PrintReason.GIVE_TO_PATIENT.wire());
        assertEquals("referral", PrintReason.REFERRAL.wire());
        assertEquals("patient_request", PrintReason.PATIENT_REQUEST.wire());
    }

    /** Every approved wire key parses back to its reason. */
    @Test
    void parsesEveryApprovedReason() {
        for (PrintReason reason : PrintReason.values()) {
            assertEquals(reason, PrintReason.parse(reason.wire()));
        }
    }

    /** A missing reason is refused with a machine-readable 422. */
    @Test
    void refusesMissingReason() {
        ApiException error = assertThrows(ApiException.class, () -> PrintReason.parse(null));
        assertEquals(422, error.status());
        assertEquals("INVALID_REASON", error.code());
    }

    /** A blank reason is refused. */
    @Test
    void refusesBlankReason() {
        ApiException error = assertThrows(ApiException.class, () -> PrintReason.parse("   "));
        assertEquals(422, error.status());
        assertEquals("INVALID_REASON", error.code());
    }

    /** A reason outside the closed list is refused. */
    @Test
    void refusesUnknownReason() {
        ApiException error = assertThrows(ApiException.class, () -> PrintReason.parse("marketing"));
        assertEquals(422, error.status());
        assertEquals("INVALID_REASON", error.code());
    }
}
