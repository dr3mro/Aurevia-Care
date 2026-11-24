package com.aurevia.it.schedulingcheckin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.scheduling.checkin.WorkflowInputs;

/** S-G1-02 pure guards: every branch of the input boundary before any SQL. */
class WorkflowInputsTest {

    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void uuidAcceptsOnlyOpaqueIdentifiers() {
        String valid = "11111111-1111-1111-1111-111111111111";
        assertEquals(valid, WorkflowInputs.uuid(valid, "patientId"));
        assertEquals(422, reject(() -> WorkflowInputs.uuid(null, "patientId")).status());
        assertEquals("INVALID_PATIENTID", reject(() -> WorkflowInputs.uuid("no", "patientId")).code());
    }

    @Test
    void codeAcceptsOnlyBoundedUppercaseCodes() {
        assertEquals("MAIN", WorkflowInputs.code("MAIN", "facilityCode"));
        assertEquals("R51_A-9", WorkflowInputs.code("R51_A-9", "facilityCode"));
        assertEquals("A".repeat(32), WorkflowInputs.code("A".repeat(32), "facilityCode"));
        assertEquals(422, reject(() -> WorkflowInputs.code(null, "facilityCode")).status());
        assertEquals(422, reject(() -> WorkflowInputs.code("m", "facilityCode")).status());
        assertEquals(422, reject(() -> WorkflowInputs.code("lower", "facilityCode")).status());
        assertEquals("INVALID_FACILITYCODE",
                reject(() -> WorkflowInputs.code("lower", "facilityCode")).code());
        assertEquals(422, reject(() -> WorkflowInputs.code("A".repeat(33), "facilityCode")).status());
        assertEquals(422, reject(() -> WorkflowInputs.code("رمز", "facilityCode")).status());
    }

    @Test
    void textRequiresOneToTwoHundredCharacters() {
        assertEquals("reason", WorkflowInputs.text("  reason  ", "reason"));
        assertEquals(422, reject(() -> WorkflowInputs.text(null, "reason")).status());
        assertEquals(422, reject(() -> WorkflowInputs.text("   ", "reason")).status());
        assertEquals(422, reject(() -> WorkflowInputs.text("x".repeat(201), "reason")).status());
    }

    @Test
    void reasonIsOptionalButBounded() {
        assertNull(WorkflowInputs.reason(null));
        assertNull(WorkflowInputs.reason("  "));
        assertEquals("patient request", WorkflowInputs.reason(" patient request "));
        assertEquals(422, reject(() -> WorkflowInputs.reason("x".repeat(201))).status());
    }

    @Test
    void instantRequiresIso8601() {
        Instant value = Instant.parse("2026-12-07T07:00:00Z");
        assertEquals(value, WorkflowInputs.instant(value.toString(), "startsAt"));
        assertEquals(422, reject(() -> WorkflowInputs.instant(null, "startsAt")).status());
        assertEquals("INVALID_STARTSAT", reject(() -> WorkflowInputs.instant("tomorrow", "startsAt")).code());
    }

    @Test
    void priorityFallsBackSafelyForAbsentOrMalformedConfiguration() {
        assertEquals(0, WorkflowInputs.priority(null));
        assertEquals(0, WorkflowInputs.priority("high"));
        assertEquals(0, WorkflowInputs.priority("-1"));
        assertEquals(0, WorkflowInputs.priority("10"));
        assertEquals(7, WorkflowInputs.priority(" 7 "));
    }

    @Test
    void methodAcceptsOnlyTheTwoDocumentedPaths() {
        assertEquals("two_identifiers", WorkflowInputs.method("two_identifiers"));
        assertEquals("documented_alternate", WorkflowInputs.method("documented_alternate"));
        assertEquals(422, reject(() -> WorkflowInputs.method(null)).status());
        assertEquals(422, reject(() -> WorkflowInputs.method("self_asserted")).status());
    }

    @Test
    void identifiersNeedTwoDistinctCategoriesAndAtLeastOneStrong() {
        assertEquals(List.of("MRN", "DOB"),
                WorkflowInputs.identifiers(Arrays.asList("mrn", " DOB ")));
        assertEquals(List.of("GOV_ID", "PATIENT_ACCOUNT"),
                WorkflowInputs.identifiers(List.of("GOV_ID", "PATIENT_ACCOUNT")));

        assertEquals(422, reject(() -> WorkflowInputs.identifiers(null)).status());
        assertEquals(422, reject(() -> WorkflowInputs.identifiers(List.of("MRN"))).status());
        assertEquals(422, reject(() -> WorkflowInputs.identifiers(
                List.of("MRN", "GOV_ID", "DOB", "PATIENT_ACCOUNT", "MRN", "GOV_ID", "DOB"))).status());
        assertEquals("INVALID_IDENTIFIERS",
                reject(() -> WorkflowInputs.identifiers(Arrays.asList("MRN", null))).code());
        assertEquals("INVALID_IDENTIFIERS",
                reject(() -> WorkflowInputs.identifiers(List.of("MRN", "PHONE"))).code());
        assertEquals("INVALID_IDENTIFIERS",
                reject(() -> WorkflowInputs.identifiers(List.of("MRN", "MRN"))).code());
        assertEquals("INVALID_IDENTIFIERS",
                reject(() -> WorkflowInputs.identifiers(List.of("DOB", "DOB"))).code());
        assertEquals("INSUFFICIENT_IDENTIFIERS",
                reject(() -> WorkflowInputs.identifiers(List.of("DOB", "VISIT_DATE"))).code());
    }

    @Test
    void alternatePathNeedsBothANamedCodeAndAReason() {
        assertEquals("SITE_APPROVED_BACKUP",
                WorkflowInputs.alternate(" SITE_APPROVED_BACKUP ", "documented outage"));
        assertEquals(422, reject(() -> WorkflowInputs.alternate(null, "reason")).status());
        assertEquals(422, reject(() -> WorkflowInputs.alternate("CODE", null)).status());
        assertEquals(422, reject(() -> WorkflowInputs.alternate("CODE", "   ")).status());
    }

    @Test
    void newIdIsAnOpaqueUuid() {
        String id = WorkflowInputs.newId();
        assertNotNull(id);
        assertTrue(id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
    }
}
