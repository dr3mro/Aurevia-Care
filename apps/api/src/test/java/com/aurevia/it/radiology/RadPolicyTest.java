package com.aurevia.it.radiology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadPolicyCatalog;
import com.aurevia.radiology.RadPolicyCatalog.InterpreterPrivilege;
import com.aurevia.radiology.RadRules;
import com.aurevia.radiology.RadTypes.Modality;

/** Approved radiology configuration and role boundaries. */
class RadPolicyTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private static RadPolicyCatalog catalog() {
        return new RadPolicyCatalog(
                List.of(Modality.CT, Modality.MRI),
                List.of(new InterpreterPrivilege("dr-x", Modality.CT, true, FROM, null),
                        new InterpreterPrivilege("dr-y", Modality.MRI, false, FROM, null),
                        new InterpreterPrivilege("dr-z", Modality.CT, true, AT.plusSeconds(60), null),
                        new InterpreterPrivilege("dr-w", Modality.CT, true, FROM, AT.minusSeconds(1))));
    }

    @Test
    void modalityApprovalIsExplicit() {
        RadPolicyCatalog catalog = catalog();
        assertTrue(catalog.modalityApproved(Modality.CT));
        assertTrue(catalog.modalityApproved(Modality.MRI));
        assertFalse(catalog.modalityApproved(Modality.PET));
        assertFalse(catalog.modalityApproved(null));
    }

    @Test
    void interpretationRequiresAnApprovedEffectivePrivilegeForTheModality() {
        RadPolicyCatalog catalog = catalog();
        assertTrue(catalog.mayInterpret("dr-x", Modality.CT, AT));
        assertFalse(catalog.mayInterpret("nobody", Modality.CT, AT));
        assertFalse(catalog.mayInterpret("dr-x", Modality.MRI, AT));
        assertFalse(catalog.mayInterpret("dr-y", Modality.MRI, AT));
        assertFalse(catalog.mayInterpret("dr-z", Modality.CT, AT));
        assertFalse(catalog.mayInterpret("dr-w", Modality.CT, AT));
    }

    @Test
    void privilegeWindowsAreEffectiveDated() {
        InterpreterPrivilege open = new InterpreterPrivilege("dr-x", Modality.CT, true, FROM, null);
        assertTrue(open.effectiveAt(FROM));
        assertTrue(open.effectiveAt(AT));
        InterpreterPrivilege bounded = new InterpreterPrivilege("dr-x", Modality.CT, true, FROM, AT);
        assertTrue(bounded.effectiveAt(FROM));
        assertFalse(bounded.effectiveAt(AT));
        assertFalse(new InterpreterPrivilege("dr-x", Modality.CT, false, FROM, null).effectiveAt(AT));
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertEquals422(() -> new RadPolicyCatalog(null, List.of()));
        assertEquals422(() -> new RadPolicyCatalog(List.of(Modality.CT), null));
        assertEquals422(() -> new InterpreterPrivilege(" ", Modality.CT, true, FROM, null));
        assertEquals422(() -> new InterpreterPrivilege(null, Modality.CT, true, FROM, null));
        assertEquals422(() -> new InterpreterPrivilege("dr-x", null, true, FROM, null));
        assertEquals422(() -> new InterpreterPrivilege("dr-x", Modality.CT, true, null, null));
    }

    @Test
    void roleBoundariesMirrorTheSectionTwoRoleTable() {
        Set<String> reception = Set.of(RadRules.RECEPTION);
        Set<String> staff = Set.of(RadRules.RADIOLOGY_STAFF);
        Set<String> interpreter = Set.of(RadRules.INTERPRETER);
        Set<String> none = Set.of("finance");

        assertTrue(RadRules.mayIntake(reception));
        assertTrue(RadRules.mayIntake(staff));
        assertFalse(RadRules.mayIntake(interpreter));
        assertTrue(RadRules.maySchedule(reception));
        assertFalse(RadRules.maySchedule(none));
        assertTrue(RadRules.mayAccession(staff));
        assertFalse(RadRules.mayAccession(reception));
        assertTrue(RadRules.mayAssociate(staff));
        assertFalse(RadRules.mayAssociate(none));
        assertTrue(RadRules.mayInterpret(interpreter));
        assertFalse(RadRules.mayInterpret(staff));
        assertTrue(RadRules.mayRecordCritical(interpreter));
        assertTrue(RadRules.mayRecordCritical(staff));
        assertFalse(RadRules.mayRecordCritical(none));
    }

    private static void assertEquals422(Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertTrue(failure.status() == 422);
    }
}
