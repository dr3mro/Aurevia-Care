package com.aurevia.it.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.authz.specialty.SpecialtyActions;
import com.aurevia.authz.specialty.SpecialtyContract.Attestation;
import com.aurevia.authz.specialty.SpecialtyContract.AttestationRequest;
import com.aurevia.authz.specialty.SpecialtyContract.PermissionView;
import com.aurevia.authz.specialty.SpecialtyDecision;
import com.aurevia.authz.specialty.SpecialtyInputs;
import com.aurevia.authz.specialty.SpecialtyRoles;

/**
 * Fast, database-free acceptance for the S-G4-06 specialty permission contract.
 *
 * <p>The exhaustive role/action matrix is the safety-critical part of PRD-06:
 * separation of duties is structural and a role can never act outside its
 * workflow. HTTP and real-PostgreSQL evidence lives in
 * {@link SpecialtyAuthorizationFlowTest}.</p>
 */
class SpecialtyPolicyTest {

    private final SpecialtyActions actions = new SpecialtyActions();

    @Test
    void roleKeysAreDistinctAndSpecialtyRolesAreNamed() {
        List<String> roles = List.of(SpecialtyRoles.LAB_COLLECTOR, SpecialtyRoles.LAB_VERIFIER,
                SpecialtyRoles.RADIOLOGY_STAFF, SpecialtyRoles.RADIOLOGY_INTERPRETER,
                SpecialtyRoles.PT_CLINICIAN, SpecialtyRoles.PHARMACY_STAFF,
                SpecialtyRoles.CLINICIAN, SpecialtyRoles.TENANT_ADMIN,
                SpecialtyRoles.PRIVACY_AUDITOR, SpecialtyRoles.RECEPTION);
        assertEquals(10, roles.stream().distinct().count());
        assertTrue(roles.contains("radiology_interpreter"));
        assertTrue(roles.contains("pharmacy_staff"));
    }

    @Test
    void actionMatrixSeparatesEverySpecialtyDuty() {
        Map<String, List<String>> allowed = Map.of(
                SpecialtyActions.LAB_ACCESSION, List.of(SpecialtyRoles.LAB_COLLECTOR),
                SpecialtyActions.LAB_COLLECT, List.of(SpecialtyRoles.LAB_COLLECTOR),
                SpecialtyActions.LAB_VERIFY_RELEASE, List.of(SpecialtyRoles.LAB_VERIFIER),
                SpecialtyActions.RAD_ASSOCIATE_STUDY, List.of(SpecialtyRoles.RADIOLOGY_STAFF),
                SpecialtyActions.RAD_SIGN_INTERPRETATION, List.of(SpecialtyRoles.RADIOLOGY_INTERPRETER),
                SpecialtyActions.PT_ACCEPT_REFERRAL, List.of(SpecialtyRoles.PT_CLINICIAN),
                SpecialtyActions.PT_SIGN_DISCHARGE, List.of(SpecialtyRoles.PT_CLINICIAN),
                SpecialtyActions.PHARMACY_REVIEW, List.of(SpecialtyRoles.PHARMACY_STAFF),
                SpecialtyActions.PHARMACY_STATUS, List.of(SpecialtyRoles.PHARMACY_STAFF));
        List<String> roles = List.of(SpecialtyRoles.LAB_COLLECTOR, SpecialtyRoles.LAB_VERIFIER,
                SpecialtyRoles.RADIOLOGY_STAFF, SpecialtyRoles.RADIOLOGY_INTERPRETER,
                SpecialtyRoles.PT_CLINICIAN, SpecialtyRoles.PHARMACY_STAFF,
                SpecialtyRoles.CLINICIAN, SpecialtyRoles.TENANT_ADMIN,
                SpecialtyRoles.PRIVACY_AUDITOR, SpecialtyRoles.RECEPTION);
        for (Map.Entry<String, List<String>> entry : allowed.entrySet()) {
            for (String role : roles) {
                assertEquals(entry.getValue().contains(role), actions.allows(entry.getKey(), role),
                        entry.getKey() + " / " + role);
            }
        }
    }

    @Test
    void attestationIsReservedForClinicalDecisionRoles() {
        List<String> attesters = List.of(SpecialtyRoles.LAB_VERIFIER,
                SpecialtyRoles.RADIOLOGY_INTERPRETER, SpecialtyRoles.PT_CLINICIAN,
                SpecialtyRoles.CLINICIAN);
        for (String role : List.of(SpecialtyRoles.LAB_COLLECTOR, SpecialtyRoles.RADIOLOGY_STAFF,
                SpecialtyRoles.PHARMACY_STAFF, SpecialtyRoles.TENANT_ADMIN,
                SpecialtyRoles.PRIVACY_AUDITOR, SpecialtyRoles.RECEPTION)) {
            assertFalse(actions.allows(SpecialtyActions.ATTEST, role), role + " cannot attest");
        }
        for (String role : attesters) {
            assertTrue(actions.allows(SpecialtyActions.ATTEST, role), role + " can attest");
        }
    }

    @Test
    void readPermissionsKeepClinicalContentOutOfAdministrativeRoles() {
        for (String role : List.of(SpecialtyRoles.LAB_COLLECTOR, SpecialtyRoles.LAB_VERIFIER,
                SpecialtyRoles.RADIOLOGY_STAFF, SpecialtyRoles.RADIOLOGY_INTERPRETER,
                SpecialtyRoles.PT_CLINICIAN, SpecialtyRoles.PHARMACY_STAFF, SpecialtyRoles.CLINICIAN)) {
            assertTrue(actions.allows(SpecialtyActions.ATTESTATION_READ, role));
        }
        for (String role : List.of(SpecialtyRoles.TENANT_ADMIN, SpecialtyRoles.PRIVACY_AUDITOR,
                SpecialtyRoles.RECEPTION)) {
            assertFalse(actions.allows(SpecialtyActions.ATTESTATION_READ, role));
        }
        for (String role : List.of(SpecialtyRoles.LAB_COLLECTOR, SpecialtyRoles.LAB_VERIFIER,
                SpecialtyRoles.RADIOLOGY_STAFF, SpecialtyRoles.RADIOLOGY_INTERPRETER,
                SpecialtyRoles.PT_CLINICIAN, SpecialtyRoles.PHARMACY_STAFF, SpecialtyRoles.CLINICIAN,
                SpecialtyRoles.TENANT_ADMIN, SpecialtyRoles.PRIVACY_AUDITOR, SpecialtyRoles.RECEPTION)) {
            assertTrue(actions.allows(SpecialtyActions.PERMISSIONS_READ, role));
        }
        assertFalse(actions.allows(SpecialtyActions.PERMISSIONS_READ, "unknown-role"));
        assertFalse(actions.allows("UNKNOWN_ACTION", SpecialtyRoles.CLINICIAN));
        assertFalse(actions.allows(null, null));
        assertFalse(actions.allows(SpecialtyActions.ATTEST, null));
    }

    @Test
    void allowedActionsIsTheStableUnionOfActiveRoles() {
        List<String> onlyVerifier = actions.allowedActions(List.of(SpecialtyRoles.LAB_VERIFIER));
        assertTrue(onlyVerifier.contains(SpecialtyActions.LAB_VERIFY_RELEASE));
        assertTrue(onlyVerifier.contains(SpecialtyActions.ATTEST));
        assertFalse(onlyVerifier.contains(SpecialtyActions.LAB_ACCESSION));
        List<String> empty = actions.allowedActions(List.of());
        assertTrue(empty.isEmpty());
        assertEquals(onlyVerifier, actions.allowedActions(List.of(SpecialtyRoles.LAB_VERIFIER)));
    }

    @Test
    void onlyClinicalDecisionActionsAreAttestableAndMapToPrd06Decisions() {
        Map<String, SpecialtyDecision> expected = Map.of(
                SpecialtyActions.LAB_VERIFY_RELEASE, SpecialtyDecision.RELEASE,
                SpecialtyActions.RAD_SIGN_INTERPRETATION, SpecialtyDecision.DIAGNOSIS,
                SpecialtyActions.PT_ACCEPT_REFERRAL, SpecialtyDecision.TREATMENT,
                SpecialtyActions.PT_SIGN_DISCHARGE, SpecialtyDecision.TREATMENT);
        for (Map.Entry<String, SpecialtyDecision> entry : expected.entrySet()) {
            assertTrue(actions.isAttestable(entry.getKey()));
            assertEquals(entry.getValue(), actions.decisionFor(entry.getKey()));
        }
        assertFalse(actions.isAttestable(SpecialtyActions.LAB_ACCESSION));
        assertFalse(actions.isAttestable(SpecialtyActions.PHARMACY_REVIEW));
        assertFalse(actions.isAttestable("UNKNOWN_ACTION"));
        assertThrows(IllegalArgumentException.class, () -> actions.decisionFor(SpecialtyActions.LAB_ACCESSION));
    }

    @Test
    void decisionWireAndParseRoundTrip() {
        for (SpecialtyDecision decision : SpecialtyDecision.values()) {
            assertEquals(decision, SpecialtyDecision.from(decision.wire()));
        }
        assertEquals("DIAGNOSIS", SpecialtyDecision.DIAGNOSIS.wire());
        assertEquals("TREATMENT", SpecialtyDecision.TREATMENT.wire());
        assertEquals("RELEASE", SpecialtyDecision.RELEASE.wire());
        assertThrows(IllegalArgumentException.class, () -> SpecialtyDecision.from("bogus"));
    }

    @Test
    void uuidValidationIsBounded() {
        String valid = "11111111-1111-1111-1111-111111111111";
        assertEquals(valid, SpecialtyInputs.uuid(valid, "patientId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> SpecialtyInputs.uuid(null, "patientId")).status());
        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> SpecialtyInputs.uuid("nope", "patientId")).code());
    }

    @Test
    void actionAndRoleValidationAreBounded() {
        assertEquals(SpecialtyActions.LAB_VERIFY_RELEASE,
                SpecialtyInputs.action(SpecialtyActions.LAB_VERIFY_RELEASE));
        assertEquals(422, assertThrows(ApiException.class, () -> SpecialtyInputs.action(null)).status());
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class,
                () -> SpecialtyInputs.action("nope")).code());
        assertEquals(SpecialtyRoles.LAB_VERIFIER, SpecialtyInputs.role(SpecialtyRoles.LAB_VERIFIER));
        assertEquals(422, assertThrows(ApiException.class, () -> SpecialtyInputs.role(null)).status());
        assertEquals("INVALID_ROLE", assertThrows(ApiException.class,
                () -> SpecialtyInputs.role("nope")).code());
    }

    @Test
    void boundedTextValidationRejectsBlankAndOversizedFields() {
        assertEquals("verified release", SpecialtyInputs.text("  verified release  ", "attestedText", 1000));
        assertEquals(422, assertThrows(ApiException.class,
                () -> SpecialtyInputs.text(null, "attestedText", 1000)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> SpecialtyInputs.text("   ", "attestedText", 1000)).status());
        assertEquals("INVALID_ATTESTEDTEXT", assertThrows(ApiException.class,
                () -> SpecialtyInputs.text("x".repeat(1001), "attestedText", 1000)).code());
        assertEquals("ORD-1", SpecialtyInputs.text("ORD-1", "resourceRef", 128));
        assertEquals(422, assertThrows(ApiException.class,
                () -> SpecialtyInputs.text("x".repeat(129), "resourceRef", 128)).status());
    }

    @Test
    void newIdIsOpaque() {
        String id = SpecialtyInputs.newId();
        assertEquals(UUID.fromString(id).toString(), id);
        assertFalse(id.equals(SpecialtyInputs.newId()));
    }

    @Test
    void recordValueSemanticsAndDefensiveCopiesAreStable() {
        AttestationRequest request = new AttestationRequest("action", "role", "patient", "ref", "text");
        assertEquals(request, new AttestationRequest("action", "role", "patient", "ref", "text"));
        assertNotNull(request.toString());
        Attestation attestation = new Attestation("id", 1, "patient", "action", "role",
                SpecialtyDecision.RELEASE, "subject", "actor", 3, "ref", "text", null);
        assertEquals(attestation, new Attestation("id", 1, "patient", "action", "role",
                SpecialtyDecision.RELEASE, "subject", "actor", 3, "ref", "text", null));
        assertNotNull(attestation.toString());
        List<String> roles = new ArrayList<>(List.of("lab_verifier"));
        List<String> actionsList = new ArrayList<>(List.of("SPEC_ATTEST"));
        PermissionView view = new PermissionView("subject", "org", roles, actionsList);
        roles.clear();
        actionsList.clear();
        assertEquals(1, view.roles().size());
        assertEquals(1, view.actions().size());
        assertEquals(view, new PermissionView("subject", "org", List.of("lab_verifier"), List.of("SPEC_ATTEST")));
        assertNotNull(view.toString());
    }
}
