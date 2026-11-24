package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/**
 * Synthetic fixtures shared by the cross-cutting section 11 acceptance tests.
 *
 * <p>Two independent synthetic clinics and one synthetic partner laboratory are
 * represented by distinct opaque organization identifiers. Every subject and
 * patient identifier below is fabricated; no real organization, credential or
 * patient data is used. The helpers bind and clear the thread-local tenant
 * context so each scenario can exercise the real domain services under an
 * explicit tenant scope.</p>
 */
final class ScenarioSupport {

    static final String CLINIC_ALPHA = "11111111-1111-1111-1111-111111111111";
    static final String CLINIC_BETA = "22222222-2222-2222-2222-222222222222";
    static final String PARTNER_LAB = "33333333-3333-3333-3333-333333333333";

    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String OTHER_PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    static final String RECEPTION = "syn-reception";
    static final String CLINICIAN = "syn-clinician";
    static final String LAB_COLLECTOR = "syn-lab-collector";
    static final String LAB_VERIFIER = "syn-lab-verifier";
    static final String PATIENT_SUBJECT = "syn-patient";
    static final String PRIVACY_OFFICER = "syn-privacy-officer";

    static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private ScenarioSupport() {
    }

    /** Bind a synthetic tenant scope for one step of a scenario. */
    static void bind(String organization, String subject, String purpose, String correlation) {
        TenantContext.set(new TenantContext(organization, subject, purpose, correlation));
    }

    /** Bind a default synthetic scope used by the service-level scenarios. */
    static void bind(String organization, String subject) {
        bind(organization, subject, TenantContext.PURPOSE, "scenario-cross-cutting");
    }

    /** Clear the thread-local tenant scope after a scenario step. */
    static void clear() {
        TenantContext.clear();
    }

    /** Assert a domain command is refused with the expected status and code. */
    static ApiException assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }
}
