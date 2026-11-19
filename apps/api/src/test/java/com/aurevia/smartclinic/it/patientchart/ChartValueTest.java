package com.aurevia.smartclinic.it.patientchart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.patient.chart.ChartActionPolicy;
import com.aurevia.smartclinic.patient.chart.ChartEntry;
import com.aurevia.smartclinic.patient.chart.ChartRepository.CatalogHit;
import com.aurevia.smartclinic.patient.chart.ChartView;
import com.aurevia.smartclinic.patient.chart.EntrySource;
import com.aurevia.smartclinic.patient.chart.EntryType;
import com.aurevia.smartclinic.patient.chart.PresenceState;
import com.aurevia.smartclinic.patient.chart.RecordEntryCommand;
import com.aurevia.smartclinic.patient.chart.VerificationState;

/** Fast coverage of the CLN-03 value types and their contract tokens. */
class ChartValueTest {

    private static final Instant TIME = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void enumTokensMatchTheDatabaseChecks() {
        assertArrayEquals(new EntryType[] {EntryType.PROBLEM, EntryType.ALLERGY,
                EntryType.MEDICATION, EntryType.VITAL}, EntryType.values());
        assertArrayEquals(new PresenceState[] {PresenceState.PRESENT, PresenceState.ABSENT,
                PresenceState.UNKNOWN}, PresenceState.values());
        assertArrayEquals(new VerificationState[] {VerificationState.UNVERIFIED,
                VerificationState.VERIFIED}, VerificationState.values());
        assertArrayEquals(new EntrySource[] {EntrySource.PATIENT_REPORTED,
                EntrySource.CLINICIAN_OBSERVED, EntrySource.EXTERNAL_RECORD, EntrySource.DEVICE},
                EntrySource.values());
        assertEquals(EntryType.VITAL, EntryType.valueOf("VITAL"));
        assertEquals(PresenceState.ABSENT, PresenceState.valueOf("ABSENT"));
        assertEquals(VerificationState.VERIFIED, VerificationState.valueOf("VERIFIED"));
        assertEquals(EntrySource.DEVICE, EntrySource.valueOf("DEVICE"));
        assertThrows(IllegalArgumentException.class, () -> EntryType.valueOf("NOPE"));
    }

    @Test
    void chartEntryExposesEveryProvenanceField() {
        ChartEntry entry = entry();
        assertEquals("e-1", entry.id());
        assertEquals("p-1", entry.patientId());
        assertEquals("enc-1", entry.encounterId());
        assertEquals(EntryType.VITAL, entry.type());
        assertEquals("temperature", entry.originalText());
        assertEquals("TEMP", entry.originalCode());
        assertEquals("local-vitals", entry.codeSystem());
        assertEquals("C", entry.mappedCode());
        assertEquals("Celsius", entry.mappedText());
        assertEquals(Integer.valueOf(2), entry.catalogVersion());
        assertEquals(EntrySource.DEVICE, entry.source());
        assertEquals(PresenceState.PRESENT, entry.presence());
        assertEquals("reason", entry.unknownReason());
        assertEquals(VerificationState.VERIFIED, entry.verification());
        assertEquals("dr-a", entry.verifiedBy());
        assertEquals(TIME, entry.verifiedAt());
        assertEquals(TIME, entry.effectiveFrom());
        assertEquals(TIME, entry.effectiveTo());
        assertEquals(TIME, entry.measuredAt());
        assertEquals("37.1", entry.value());
        assertEquals("Celsius", entry.unit());
        assertEquals("dr-a", entry.clinicalAuthor());
        assertEquals("assistant-a", entry.dataEntryActor());
        assertEquals(2, entry.version());
        assertEquals(TIME, entry.recordedAt());
        assertEquals(entry, entry());
        assertEquals(entry.hashCode(), entry().hashCode());
        assertNotNull(entry.toString());
        assertNotEquals(entry, new CatalogHit("x", "y", 1));
    }

    @Test
    void commandCarriesTheSubmittedFields() {
        RecordEntryCommand command = new RecordEntryCommand("p-1", "enc-1", EntryType.PROBLEM,
                "headache", "R51", "diagnosis", EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, TIME, null, null, null, null, "dr-a");
        assertEquals("p-1", command.patientId());
        assertEquals("enc-1", command.encounterId());
        assertEquals(EntryType.PROBLEM, command.type());
        assertEquals("headache", command.originalText());
        assertEquals("R51", command.originalCode());
        assertEquals("diagnosis", command.codeSystem());
        assertEquals(EntrySource.PATIENT_REPORTED, command.source());
        assertEquals(PresenceState.PRESENT, command.presence());
        assertEquals(null, command.unknownReason());
        assertEquals(TIME, command.effectiveFrom());
        assertEquals(null, command.effectiveTo());
        assertEquals(null, command.measuredAt());
        assertEquals(null, command.value());
        assertEquals(null, command.unit());
        assertEquals("dr-a", command.clinicalAuthor());
        assertEquals(command, new RecordEntryCommand("p-1", "enc-1", EntryType.PROBLEM,
                "headache", "R51", "diagnosis", EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, TIME, null, null, null, null, "dr-a"));
        assertNotNull(command.toString());
    }

    @Test
    void catalogHitIsAValueSnapshot() {
        CatalogHit hit = new CatalogHit("R51", "Headache", 3);
        assertEquals("R51", hit.code());
        assertEquals("Headache", hit.text());
        assertEquals(3, hit.version());
        assertEquals(hit, new CatalogHit("R51", "Headache", 3));
        assertNotEquals(hit, new CatalogHit("R52", "Headache", 3));
        assertNotNull(hit.toString());
    }

    @Test
    void chartViewCopiesOnStoreAndRead() {
        List<ChartEntry> mutable = new java.util.ArrayList<>(List.of(entry()));
        ChartView view = new ChartView(mutable);
        mutable.clear();
        assertEquals(1, view.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
        ChartView direct = new ChartView(List.of(entry()));
        assertEquals(direct, new ChartView(List.of(entry())));
        assertEquals(direct.hashCode(), new ChartView(List.of(entry())).hashCode());
        assertTrue(direct.toString().contains("ChartEntry"));
        assertNotEquals(direct, new ChartView(List.of()));
        assertThrows(NullPointerException.class, () -> new ChartView(null));
    }

    private static ChartEntry entry() {
        return new ChartEntry("e-1", "p-1", "enc-1", EntryType.VITAL, "temperature", "TEMP",
                "local-vitals", "C", "Celsius", 2, EntrySource.DEVICE, PresenceState.PRESENT,
                "reason", VerificationState.VERIFIED, "dr-a", TIME, TIME, TIME, TIME, "37.1",
                "Celsius", "dr-a", "assistant-a", 2, TIME);
    }
}

/** Fast unit coverage for the CLN-03 chart role matrix. */
class ChartActionPolicyTest {

    private final ChartActionPolicy policy = new ChartActionPolicy();

    @Test
    void clinicianMayReadCreateAndVerify() {
        assertTrue(policy.allows(ChartActionPolicy.CHART_READ, "clinician"));
        assertTrue(policy.allows(ChartActionPolicy.CHART_ENTRY_CREATE, "clinician"));
        assertTrue(policy.allows(ChartActionPolicy.CHART_ENTRY_VERIFY, "clinician"));
    }

    @Test
    void assistantMayReadAndCreateButNotVerify() {
        assertTrue(policy.allows(ChartActionPolicy.CHART_READ, "clinical_assistant"));
        assertTrue(policy.allows(ChartActionPolicy.CHART_ENTRY_CREATE, "clinical_assistant"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_ENTRY_VERIFY, "clinical_assistant"));
    }

    @Test
    void nonClinicalAndUnknownRolesAreDenied() {
        assertFalse(policy.allows(ChartActionPolicy.CHART_READ, "reception"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_ENTRY_CREATE, "tenant_admin"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_ENTRY_VERIFY, "privacy_auditor"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_READ, "unknown_role"));
        assertFalse(policy.allows("UNKNOWN_ACTION", "clinician"));
    }

    @Test
    void nullActionAndRoleFailClosed() {
        assertFalse(policy.allows(null, "clinician"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_READ, null));
        assertFalse(policy.allows(null, null));
    }
}
