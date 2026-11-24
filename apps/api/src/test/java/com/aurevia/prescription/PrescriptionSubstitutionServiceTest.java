package com.aurevia.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.rx.RxRepository;

/** RX-02 authoritative substitution application: entitlement, ownership and version gates. */
class PrescriptionSubstitutionServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PRESCRIBER = "prescriber-substitution-unit";
    private static final String PRESCRIPTION = "22222222-2222-2222-2222-222222222222";

    private PrescriptionRepository repository;
    private RxRepository prescribing;
    private PrescriptionSubstitutionService applier;
    private DrugEntry replacement;

    @BeforeEach
    void setUp() {
        repository = mock(PrescriptionRepository.class);
        prescribing = mock(RxRepository.class);
        applier = new PrescriptionSubstitutionService(repository, prescribing);
        replacement = new DrugEntry("drug-replacement", 1, "Paracetamol", "Calpol", "tablet",
                "500", "mg", java.util.List.of(), true, java.time.Instant.parse("2026-01-01T00:00:00Z"),
                null, "synthetic-catalog-v1");
    }

    private Draft draft(int version, String prescriber) {
        return new Draft(PRESCRIPTION, version, "patient-1", "draft", "drug-original", 1,
                "Paracetamol", "500", "mg", "oral", "twice daily", "60", "tablet", null,
                "take with water", "pharmacy-a", null, prescriber,
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void entitled() {
        when(prescribing.approvedSetting("rx02.market")).thenReturn(Optional.of("synthetic-market"));
        when(prescribing.approvedSetting("rx02.entitlement." + PRESCRIBER))
                .thenReturn(Optional.of("enabled"));
    }

    @Test
    void entitledPrescriberAppliesTheReplacementThroughTheDraftWorkflow() {
        when(repository.loadForUpdate(PRESCRIPTION)).thenReturn(Optional.of(draft(1, PRESCRIBER)));
        when(repository.currentCatalogVersion()).thenReturn(
                new com.aurevia.catalog.MedicationCatalogVersion(
                        2, "synthetic-market", java.time.Instant.parse("2026-01-01T00:00:00Z"), "v2"));
        entitled();

        applier.apply(ORG, PRESCRIBER, PRESCRIPTION, 1, replacement);

        verify(repository).selectMedication(PRESCRIPTION, 1, "drug-replacement", 1,
                "Paracetamol", "500", "mg", "oral", "twice daily", "60", "tablet", null,
                "take with water", 2);
    }

    @Test
    void missingDraftIsConcealed() {
        when(repository.loadForUpdate(PRESCRIPTION)).thenReturn(Optional.empty());
        ApiException failure = assertThrows(ApiException.class,
                () -> applier.apply(ORG, PRESCRIBER, PRESCRIPTION, 1, replacement));
        assertEquals(404, failure.status());
    }

    @Test
    void aDifferentClinicianIsDeniedBeforeAnyPrecondition() {
        when(repository.loadForUpdate(PRESCRIPTION)).thenReturn(Optional.of(draft(1, "someone-else")));
        ApiException failure = assertThrows(ApiException.class,
                () -> applier.apply(ORG, PRESCRIBER, PRESCRIPTION, 9, replacement));
        assertEquals(403, failure.status());
        verify(repository, never()).selectMedication(anyString(), anyInt(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyInt());
    }

    @Test
    void aStalePrescriptionVersionIsRejected() {
        when(repository.loadForUpdate(PRESCRIPTION)).thenReturn(Optional.of(draft(2, PRESCRIBER)));
        ApiException failure = assertThrows(ApiException.class,
                () -> applier.apply(ORG, PRESCRIBER, PRESCRIPTION, 1, replacement));
        assertEquals(412, failure.status());
    }

    @Test
    void missingMarketPolicyFailsClosed() {
        when(repository.loadForUpdate(PRESCRIPTION)).thenReturn(Optional.of(draft(1, PRESCRIBER)));
        when(prescribing.approvedSetting("rx02.market")).thenReturn(Optional.empty());
        ApiException failure = assertThrows(ApiException.class,
                () -> applier.apply(ORG, PRESCRIBER, PRESCRIPTION, 1, replacement));
        assertEquals(422, failure.status());
        assertEquals("NO_MARKET_POLICY", failure.code());
    }

    @Test
    void missingEntitlementFailsClosed() {
        when(repository.loadForUpdate(PRESCRIPTION)).thenReturn(Optional.of(draft(1, PRESCRIBER)));
        when(prescribing.approvedSetting("rx02.market")).thenReturn(Optional.of("synthetic-market"));
        when(prescribing.approvedSetting("rx02.entitlement." + PRESCRIBER)).thenReturn(Optional.empty());
        ApiException failure = assertThrows(ApiException.class,
                () -> applier.apply(ORG, PRESCRIBER, PRESCRIPTION, 1, replacement));
        assertEquals("NO_ENTITLEMENT", failure.code());
    }

    @Test
    void disabledEntitlementFailsClosed() {
        when(repository.loadForUpdate(PRESCRIPTION)).thenReturn(Optional.of(draft(1, PRESCRIBER)));
        when(prescribing.approvedSetting("rx02.market")).thenReturn(Optional.of("synthetic-market"));
        when(prescribing.approvedSetting("rx02.entitlement." + PRESCRIBER))
                .thenReturn(Optional.of("disabled"));
        ApiException failure = assertThrows(ApiException.class,
                () -> applier.apply(ORG, PRESCRIBER, PRESCRIPTION, 1, replacement));
        assertEquals("NO_ENTITLEMENT", failure.code());
        assertEquals("the prescriber is not entitled in this market", failure.getMessage());
    }
}
