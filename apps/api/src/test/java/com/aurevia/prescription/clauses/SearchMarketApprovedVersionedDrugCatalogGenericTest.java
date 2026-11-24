package com.aurevia.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.catalog.MedicationCatalogItem;
import com.aurevia.catalog.MedicationCatalogVersion;
import com.aurevia.catalog.VersionedCatalogSearch;
import com.aurevia.prescription.PrescriptionActions;
import com.aurevia.prescription.PrescriptionContract.AlternativeKind;
import com.aurevia.prescription.PrescriptionContract.CatalogHit;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Ingredient;
import com.aurevia.prescription.PrescriptionRepository;
import com.aurevia.prescription.PrescriptionService;
import com.aurevia.tenant.TenantContext;

/**
 * RX-01 clause 2 proof: a market-approved versioned drug catalog is searched by
 * generic or trade name and formulation. The service folds only approved,
 * effective, newest-version entries, reports the published catalog version and
 * attaches tagged review-only alternatives, while refusing inactive subjects and
 * malformed queries before any catalog read. All fixtures are synthetic.
 */
class SearchMarketApprovedVersionedDrugCatalogGenericTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-1";
    private static final String ENTRY = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String ALTERNATIVE = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String UNAPPROVED = "bbbbbbbb-0000-0000-0000-000000000003";
    private static final String RETIRED = "bbbbbbbb-0000-0000-0000-000000000004";
    private static final String FUTURE = "bbbbbbbb-0000-0000-0000-000000000005";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private PrescriptionRepository repository;
    private AuditAppender audit;
    private PrescriptionService service;

    /** Build the service with mocked boundaries and an inline tenant-guarded transaction. */
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
        TenantContext.set(new TenantContext(ORG, ACTOR, "PRESCRIPTION_DRAFT", "corr-clause-two"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant the catalog search permission through an active clinician membership. */
    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
    }

    /** A published catalog version with synthetic market approval provenance. */
    private static MedicationCatalogVersion version(int number) {
        return new MedicationCatalogVersion(number, "synthetic-market", FROM, "synthetic-market-v" + number);
    }

    /** One route-bearing item; the substance id binds alternatives by identity, not by name. */
    private static MedicationCatalogItem item(String entryId, int version, String generic, String trade,
            String formulation, boolean approved, Instant from, Instant to, String route) {
        return new MedicationCatalogItem(new DrugEntry(entryId, version, generic, trade, formulation,
                "500", "mg", List.of(new Ingredient("SUB", null, 0)), approved, from, to,
                "synthetic-market-v" + version), route);
    }

    /** Rule met: only approved, effective, newest-version entries match the name and formulation. */
    @Test
    void searchFindsApprovedEffectiveEntryByGenericOrTradeNameAndFormulation() {
        authorized();
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        MedicationCatalogItem current = item(ENTRY, 2, "Amoxicillin", "Amoxil", "tablet",
                true, FROM, TO, "oral");
        MedicationCatalogItem alternative = item(ALTERNATIVE, 1, "Doxycycline", "Doxy Caps", "capsule",
                true, FROM, TO, "oral");
        List<MedicationCatalogItem> items = List.of(
                current,
                item(ENTRY, 1, "Amoxicillin", "Amoxil", "tablet", true, FROM, TO, "oral"),
                alternative,
                item(UNAPPROVED, 1, "Amoxicillin", "Amoxil", "tablet", false, FROM, TO, "oral"),
                item(RETIRED, 1, "Amoxicillin", "Amoxil", "tablet", true, FROM, AT, "oral"),
                item(FUTURE, 1, "Amoxicillin", "Amoxil", "tablet", true, AT.plusSeconds(1), TO, "oral"));
        when(repository.loadCatalog()).thenReturn(items.stream().map(MedicationCatalogItem::drug).toList());
        when(repository.loadMedicationCatalog()).thenReturn(items);

        List<CatalogHit> hits = service.searchCatalog("amox", "tablet");

        assertEquals(1, hits.size(), "only the approved, effective, current entry is searchable");
        CatalogHit hit = hits.get(0);
        assertEquals(ENTRY, hit.entry().entryId());
        assertEquals(2, hit.entry().version(), "the newest effective version supersedes the older one");
        assertEquals("tablet", hit.entry().formulation());
        assertTrue(hit.entry().effectiveAt(AT));
        assertEquals(1, hit.alternatives().size());
        assertEquals(AlternativeKind.SUGGESTION, hit.alternatives().get(0).kind());
        assertEquals(ALTERNATIVE, hit.alternatives().get(0).entry().entryId());
        assertEquals("shared ingredient identity", hit.alternatives().get(0).reason());
        assertTrue(hit.alternatives().get(0).requiresClinicianSelection());
        assertEquals("synthetic-market-v1", hit.alternatives().get(0).sourceCatalogVersion());

        VersionedCatalogSearch versioned = service.searchCatalogWithVersion("AMOX", "tablet");
        assertEquals(1, versioned.version().version());
        assertEquals("synthetic-market", versioned.version().market());
        assertEquals("synthetic-market-v1", versioned.version().provenance());
        assertEquals(1, versioned.items().size(), "the non-matching alternative is not a hit");
        assertEquals(ENTRY, versioned.items().get(0).drug().entryId());
        assertEquals(2, versioned.items().get(0).drug().version());
        assertEquals("oral", versioned.items().get(0).route());
        verify(audit, times(2)).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    /** Negative: an entry whose formulation differs from the filter is not returned. */
    @Test
    void searchDoesNotReturnAnEntryWithADifferentFormulation() {
        authorized();
        when(repository.currentCatalogVersion()).thenReturn(version(1));
        List<MedicationCatalogItem> items = List.of(
                item(ENTRY, 1, "Amoxicillin", "Amoxil", "tablet", true, FROM, TO, "oral"),
                item(ALTERNATIVE, 1, "Amoxicillin", "Amoxil Caps", "capsule", true, FROM, TO, "oral"));
        when(repository.loadCatalog()).thenReturn(items.stream().map(MedicationCatalogItem::drug).toList());
        when(repository.loadMedicationCatalog()).thenReturn(items);

        List<CatalogHit> tablets = service.searchCatalog("amox", "tablet");
        assertEquals(1, tablets.size());
        assertEquals(ENTRY, tablets.get(0).entry().entryId());
        assertEquals("tablet", tablets.get(0).entry().formulation());
        assertEquals(1, tablets.get(0).alternatives().size());
        assertEquals("same generic name", tablets.get(0).alternatives().get(0).reason());

        List<CatalogHit> capsules = service.searchCatalog("amox", "capsule");
        assertEquals(1, capsules.size());
        assertEquals(ALTERNATIVE, capsules.get(0).entry().entryId());
        assertEquals("capsule", capsules.get(0).entry().formulation());

        assertTrue(service.searchCatalog("amox", "syrup").isEmpty(), "a non-matching formulation returns nothing");
    }

    /** Negative: a subject without active membership is refused before the catalog is read. */
    @Test
    void searchDeniesASubjectWithoutActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        ApiException denial = assertThrows(ApiException.class, () -> service.searchCatalog("amox", "tablet"));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository, never()).loadCatalog();
        verify(audit).append(any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("DENIED"));
    }

    /** Negative: an overlong query fails before authorization or any repository read. */
    @Test
    void searchRejectsAnOverlongQueryBeforeTouchingTheRepository() {
        ApiException invalid = assertThrows(ApiException.class,
                () -> service.searchCatalog("x".repeat(129), null));
        assertEquals(422, invalid.status());
        assertEquals("INVALID_QUERY", invalid.code());
        verify(repository, never()).hasActiveMembership(ACTOR);
        verify(repository, never()).loadCatalog();
    }
}
