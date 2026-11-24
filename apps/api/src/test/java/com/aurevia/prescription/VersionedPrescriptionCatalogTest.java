package com.aurevia.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.catalog.MedicationCatalogItem;
import com.aurevia.catalog.MedicationCatalogVersion;
import com.aurevia.catalog.VersionedCatalogSearch;
import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Ingredient;
import com.aurevia.prescription.PrescriptionContract.SelectMedicationRequest;
import com.aurevia.tenant.TenantContext;

/** BUILD-09 (RX-01): versioned catalog search, stored draft version and failure paths. */
class VersionedPrescriptionCatalogTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-1";
    private static final String DRAFT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String ENTRY = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private PrescriptionRepository repository;
    private AuditAppender audit;
    private PrescriptionService service;

    @BeforeEach
    void setUp() {
        repository = mock(PrescriptionRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new PrescriptionService(repository, new PrescriptionActions(), transactions, audit,
                Clock.fixed(AT, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, ACTOR, "PRESCRIPTION_DRAFT", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
    }

    private static MedicationCatalogVersion version(int number) {
        return new MedicationCatalogVersion(number, "synthetic-market", FROM, "synthetic-market-v" + number);
    }

    private static MedicationCatalogItem item(String route, boolean approved, Instant from, Instant to) {
        return new MedicationCatalogItem(new DrugEntry(ENTRY, 1, "Amoxicillin", "Amoxil", "tablet",
                "500", "mg", List.of(new Ingredient("SUB", null, 0)), approved, from, to,
                "synthetic-market-v1"), route);
    }

    private static Draft draft(int version) {
        return new Draft(DRAFT, version, "cccccccc-0000-0000-0000-000000000001", "draft", null, null, null,
                null, null, null, null, null, null, null, null, null, null, ACTOR, null);
    }

    private static SelectMedicationRequest select() {
        return new SelectMedicationRequest(ENTRY, "500", "mg", "oral", "once daily", "10", "tablet",
                2, "synthetic instruction", null);
    }

    @Test
    void searchReturnsTheCatalogVersionUsedAndRouteAwareItems() {
        authorized();
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        when(repository.loadMedicationCatalog()).thenReturn(List.of(
                item("oral", true, FROM, TO), item("oral", false, FROM, TO),
                item("oral", true, FROM, AT)));

        VersionedCatalogSearch result = service.searchCatalogWithVersion("amox", "tablet");

        assertEquals(1, result.version().version());
        assertEquals("synthetic-market", result.version().market());
        assertEquals(1, result.items().size(), "only the approved, effective entry is returned");
        assertEquals("oral", result.items().get(0).route());
        verify(audit).append(any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("SUCCESS"));
    }

    @Test
    void searchValidatesInputBeforeTouchingTheRepository() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.searchCatalogWithVersion("x".repeat(129), null)).status());
        verify(repository, never()).currentCatalogVersion();
    }

    @Test
    void searchDeniesAnInactiveSubject() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.searchCatalogWithVersion(null, null)).status());
        verify(repository, never()).currentCatalogVersion();
    }


    @Test
    void draftCatalogVersionIsReadThroughTheDomainService() {
        authorized();
        when(repository.find(DRAFT)).thenReturn(Optional.of(draft(1)));
        when(repository.catalogVersion(DRAFT)).thenReturn(2);

        assertEquals(2, service.catalogVersion(DRAFT).catalogVersion());
        assertEquals(DRAFT, service.catalogVersion(DRAFT).draftId());
    }

    @Test
    void draftCatalogVersionConcealsAnInvisibleDraft() {
        authorized();
        when(repository.find(DRAFT)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.catalogVersion(DRAFT)).status());
    }

    @Test
    void draftCatalogVersionValidatesItsIdentifier() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.catalogVersion("not-a-uuid")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new DraftCatalogVersion(null, 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new DraftCatalogVersion(" ", 1)).status());
        assertEquals("draft", new DraftCatalogVersion("draft", 1).draftId());
    }

    @Test
    void selectionStoresTheCatalogVersionItWasBuiltFrom() {
        authorized();
        when(repository.loadForUpdate(DRAFT)).thenReturn(Optional.of(draft(1)));
        when(repository.currentCatalogVersion()).thenReturn(version(2));
        when(repository.loadCatalog()).thenReturn(List.of(
                new DrugEntry(ENTRY, 1, "Amoxicillin", "Amoxil", "tablet", "500", "mg",
                        List.of(new Ingredient("SUB", null, 0)), true, FROM, TO, "synthetic-market-v1")));
        when(repository.catalogRoute(ENTRY, 1)).thenReturn("ORAL");
        when(repository.allergyAssessment(anyString()))
                .thenReturn(new AllergyAssessment(AllergyStatus.VERIFIED_NONE, List.of()));
        when(repository.selectMedication(anyString(), anyInt(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyInt())).thenReturn(1);
        when(repository.find(DRAFT)).thenReturn(Optional.of(draft(2)));

        service.selectMedication(DRAFT, select(), 1);

        ArgumentCaptor<Integer> captured = ArgumentCaptor.forClass(Integer.class);
        verify(repository).selectMedication(anyString(), anyInt(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                captured.capture());
        assertEquals(2, captured.getValue(), "the current catalog version is bound to the draft on selection");
    }

    @Test
    void selectionRejectsARouteThatContradictsTheApprovedCatalogRoute() {
        authorized();
        when(repository.loadForUpdate(DRAFT)).thenReturn(Optional.of(draft(1)));
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        when(repository.loadCatalog()).thenReturn(List.of(item("oral", true, FROM, TO).drug()));
        when(repository.catalogRoute(ENTRY, 1)).thenReturn("oral");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.selectMedication(DRAFT,
                        new SelectMedicationRequest(ENTRY, "500", "mg", "intrathecal", "once daily",
                                "10", "tablet", null, "synthetic", null), 1));
        assertEquals(422, failure.status());
        assertEquals("ROUTE_MISMATCH", failure.code());
        verify(repository, never()).selectMedication(anyString(), anyInt(), anyString(), anyInt(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyInt());
    }

    @Test
    void legacyUnspecifiedCatalogRouteDoesNotBlockSelection() {
        authorized();
        when(repository.loadForUpdate(DRAFT)).thenReturn(Optional.of(draft(1)));
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        when(repository.loadCatalog()).thenReturn(List.of(item("unspecified", true, FROM, TO).drug()));
        when(repository.catalogRoute(ENTRY, 1)).thenReturn("unspecified");
        when(repository.allergyAssessment(anyString()))
                .thenReturn(new AllergyAssessment(AllergyStatus.VERIFIED_NONE, List.of()));
        when(repository.selectMedication(anyString(), anyInt(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyInt())).thenReturn(1);
        when(repository.find(DRAFT)).thenReturn(Optional.of(draft(2)));

        assertEquals(2, service.selectMedication(DRAFT, select(), 1).version());
    }

    @Test
    void anUnapprovedItemCannotBeAddedToADraft() {
        authorized();
        when(repository.loadForUpdate(DRAFT)).thenReturn(Optional.of(draft(1)));
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        when(repository.loadCatalog()).thenReturn(List.of(item("oral", false, FROM, TO).drug()));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.selectMedication(DRAFT, select(), 1));
        assertEquals(422, failure.status());
        assertEquals("INVALID_CATALOG_ENTRY", failure.code());
        verify(repository, never()).selectMedication(anyString(), anyInt(), anyString(), anyInt(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyInt());
    }

    @Test
    void aRetiredItemCannotBeAddedToADraft() {
        authorized();
        when(repository.loadForUpdate(DRAFT)).thenReturn(Optional.of(draft(1)));
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        when(repository.loadCatalog()).thenReturn(List.of(item("oral", true, FROM, AT).drug()));

        assertEquals("INVALID_CATALOG_ENTRY", assertThrows(ApiException.class,
                () -> service.selectMedication(DRAFT, select(), 1)).code());
    }

    @Test
    void aFutureItemCannotBeAddedToADraft() {
        authorized();
        when(repository.loadForUpdate(DRAFT)).thenReturn(Optional.of(draft(1)));
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        when(repository.loadCatalog()).thenReturn(List.of(item("oral", true, AT.plusSeconds(1), TO).drug()));

        assertEquals("INVALID_CATALOG_ENTRY", assertThrows(ApiException.class,
                () -> service.selectMedication(DRAFT, select(), 1)).code());
    }
}
