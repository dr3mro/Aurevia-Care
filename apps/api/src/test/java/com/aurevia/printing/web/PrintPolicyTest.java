package com.aurevia.printing.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.printing.HandoutKind;

/**
 * Default-deny print permission matrix, expressed over freshly derived roles
 * and the facility of the record (IAM-01, ARC-02, CLN-07).
 *
 * <p>All identifiers, roles and facilities here are synthetic. The policy never
 * reads a role from the request; tests pass the active-role snapshot explicitly.</p>
 */
class PrintPolicyTest {

    private static final String ORG = "org-alpha";

    private static HandoutSource source(HandoutKind kind) {
        return new HandoutSource(ORG, kind, "src-1", 1, "a".repeat(64), "attested", true,
                "PRINTABLE_LOCAL", "PAT-SYN-1", "Synthetic Patient", "Dr Synthetic", "FAC-A",
                Instant.parse("2026-11-21T10:15:00Z"), "SYNTHETIC-EMR", List.of("synthetic line"));
    }

    private static Actor actor(String... roles) {
        return Actor.staff(ORG, "clinician-a", roles);
    }

    /** A treating clinician may print every kind. */
    @Test
    void clinicianMayPrintEveryKind() {
        for (HandoutKind kind : HandoutKind.values()) {
            assertTrue(PrintPolicy.allows(kind, actor("clinician").roles()));
        }
    }

    /** Lab staff may print only a lab order; each accepted role branch is taken. */
    @Test
    void labStaffMayPrintOnlyLabOrders() {
        assertTrue(PrintPolicy.allows(HandoutKind.LAB_ORDER, actor("lab_collector").roles()));
        assertTrue(PrintPolicy.allows(HandoutKind.LAB_ORDER, actor("lab_verifier").roles()));
        assertFalse(PrintPolicy.allows(HandoutKind.IMAGING_ORDER, actor("lab_collector").roles()));
    }

    /** Radiology staff may print only an imaging order; each accepted role branch is taken. */
    @Test
    void radiologyStaffMayPrintOnlyImagingOrders() {
        assertTrue(PrintPolicy.allows(HandoutKind.IMAGING_ORDER, actor("radiology_staff").roles()));
        assertTrue(PrintPolicy.allows(HandoutKind.IMAGING_ORDER, actor("radiology_interpreter").roles()));
        assertFalse(PrintPolicy.allows(HandoutKind.LAB_ORDER, actor("radiology_staff").roles()));
    }

    /** Roles without a print permission are denied for every kind. */
    @Test
    void rolesWithoutPrintPermissionAreDenied() {
        for (String role : List.of("reception", "tenant_admin", "privacy_auditor", "patient")) {
            for (HandoutKind kind : HandoutKind.values()) {
                assertFalse(PrintPolicy.allows(kind, actor(role).roles()));
            }
        }
        assertFalse(PrintPolicy.allows(HandoutKind.LAB_ORDER, Set.of()));
    }

    /** A valid clinician inside the record facility is allowed. */
    @Test
    void allowsClinicianInsideRecordFacility() {
        PrintPolicy.authorize(actor("clinician"), source(HandoutKind.PRESCRIPTION), true);
    }

    /** A missing actor fails closed. */
    @Test
    void refusesMissingActor() {
        ApiException error = assertThrows(ApiException.class,
                () -> PrintPolicy.authorize(null, source(HandoutKind.PRESCRIPTION), true));
        assertEquals(401, error.status());
        assertEquals("UNAUTHENTICATED", error.code());
    }

    /** A record from another tenant is concealed as not found. */
    @Test
    void concealsAnotherTenant() {
        HandoutSource foreign = new HandoutSource("org-beta", HandoutKind.PRESCRIPTION, "src-1", 1,
                "a".repeat(64), "attested", true, "PRINTABLE_LOCAL", "PAT-SYN-1", "Synthetic Patient",
                "Dr Synthetic", "FAC-A", Instant.parse("2026-11-21T10:15:00Z"), "SYNTHETIC-EMR",
                List.of("synthetic line"));
        ApiException error = assertThrows(ApiException.class,
                () -> PrintPolicy.authorize(actor("clinician"), foreign, true));
        assertEquals(404, error.status());
        assertEquals("NOT_FOUND", error.code());
    }

    /** A record outside the caller's facility scope is forbidden. */
    @Test
    void refusesRecordOutsideFacilityScope() {
        ApiException error = assertThrows(ApiException.class,
                () -> PrintPolicy.authorize(actor("clinician"), source(HandoutKind.PRESCRIPTION), false));
        assertEquals(403, error.status());
        assertEquals("FACILITY_OUT_OF_SCOPE", error.code());
    }

    /** A role with no print permission is forbidden even inside the facility. */
    @Test
    void refusesRoleWithoutPrintPermission() {
        ApiException error = assertThrows(ApiException.class,
                () -> PrintPolicy.authorize(actor("reception"), source(HandoutKind.PRESCRIPTION), true));
        assertEquals(403, error.status());
        assertEquals("FORBIDDEN", error.code());
    }
}
