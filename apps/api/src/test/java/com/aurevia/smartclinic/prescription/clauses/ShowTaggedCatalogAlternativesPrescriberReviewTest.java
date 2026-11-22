package com.aurevia.smartclinic.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.prescription.DrugCatalog;
import com.aurevia.smartclinic.prescription.PrescriptionActions;
import com.aurevia.smartclinic.prescription.PrescriptionContract.Alternative;
import com.aurevia.smartclinic.prescription.PrescriptionContract.AlternativeKind;
import com.aurevia.smartclinic.prescription.PrescriptionContract.CatalogHit;
import com.aurevia.smartclinic.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.smartclinic.prescription.PrescriptionContract.Ingredient;
import com.aurevia.smartclinic.prescription.PrescriptionRepository;
import com.aurevia.smartclinic.prescription.PrescriptionService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * RX-01 clause 3 proof: a clinician's catalog search shows alternatives tagged
 * as review-only suggestions, carrying a reason and their source catalog
 * version, and never replacing the selected medication. The positive case walks
 * the normal command entry point and asserts the returned
 * {@link com.aurevia.smartclinic.prescription.PrescriptionContract.CatalogHit} values;
 * negative cases show the rule refusing a role without catalog
 * permission and a subject without active membership, hiding unapproved or
 * out-of-window versions, and failing closed on an empty catalog. All catalog
 * keys, subjects and provenance strings below are synthetic and no patient data
 * is involved.
 */
class ShowTaggedCatalogAlternativesPrescriberReviewTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-1";
    private static final String SELECTED_ID = "AMOX-500";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private PrescriptionRepository repository;
    private AuditAppender audit;
    private PrescriptionService service;

    /** Build a tenant-guarded service with a mocked repository and a fixed clock. */
    private void setUpFixture() {
        repository = mock(PrescriptionRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new PrescriptionService(repository, new PrescriptionActions(), transactions, audit,
                Clock.fixed(AT, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, ACTOR, "PRESCRIPTION_DRAFT", "corr-clause-rx01-c3"));
    }

    @BeforeEach
    void setUp() {
        setUpFixture();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant one active role to the current subject in the active tenant. */
    private void activeRole(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    /** Build one synthetic catalog version with a structured ingredient identity. */
    private static DrugEntry entry(String id, int version, String generic, String trade, String formulation,
                                   String substance, String group, boolean approved, Instant from, Instant to,
                                   String provenance) {
        return new DrugEntry(id, version, generic, trade, formulation, "500", "mg",
                List.of(new Ingredient(substance, group, 1)), approved, from, to, provenance);
    }

    /** A catalog with two kinds of alternative relation, one unrelated entry and hidden versions. */
    private static List<DrugEntry> catalogWithAlternatives() {
        return List.of(
                entry(SELECTED_ID, 1, "Amoxicillin", "Amoxil", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        true, FROM, TO, "synthetic-market-v3"),
                entry("AMOX-250", 2, "amoxicillin", "Amoxil-250", "tablet", "AMOX-SUB-2", "OTHER-GROUP",
                        true, FROM, TO, "synthetic-market-v2"),
                entry("SHARED-SUB", 1, "Different Generic", "Different Trade", "capsule", "AMOX-SUB",
                        "UNRELATED-GROUP", true, FROM, TO, "synthetic-market-v1"),
                entry("SHARED-SUB", 2, "Newer Sharing Substance", "Newer Trade", "capsule", "AMOX-SUB",
                        "UNRELATED-GROUP", true, FROM, TO, "synthetic-market-v2"),
                entry("SHARED-SUB", 3, "Unapproved Newer", "Unapproved", "capsule", "AMOX-SUB",
                        "UNRELATED-GROUP", false, FROM, TO, "synthetic-market-v3"),
                entry("SHARED-GROUP", 1, "Group Drug", "Group Trade", "tablet", "OTHER-SUB", "BETA_LACTAM",
                        true, FROM, TO, "synthetic-market-v1"),
                entry("CIPRO", 1, "Ciprofloxacin", "Ciproxin", "tablet", "CIPRO-SUB", "QUINOLONE",
                        true, FROM, TO, "synthetic-market-v1"),
                entry("UNAPPROVED", 1, "Unapproved", "Unapproved", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        false, FROM, TO, "synthetic-market-v1"),
                entry("FUTURE", 1, "Future", "Future", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        true, AT.plusSeconds(1), TO, "synthetic-market-v2"),
                entry("EXPIRED", 1, "Expired", "Expired", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        true, FROM, AT, "synthetic-market-v1"));
    }

    /** Return the single hit for a catalog entry, failing when the entry is absent. */
    private static CatalogHit hitFor(List<CatalogHit> hits, String entryId) {
        return hits.stream().filter(hit -> hit.entry().entryId().equals(entryId))
                .findFirst().orElseThrow();
    }

    /** Return the tagged alternative for a catalog entry, failing when it is absent. */
    private static Alternative alternativeFor(List<Alternative> alternatives, String entryId) {
        return alternatives.stream()
                .filter(alternative -> alternative.entry().entryId().equals(entryId))
                .findFirst().orElseThrow();
    }

    /** Assert the outcome, action, scope and resource id of the catalog-search audit row. */
    private void assertAudit(String outcome) {
        verify(audit).append(any(), eq(PrescriptionActions.SEARCH_CATALOG),
                eq(PrescriptionActions.SEARCH_CATALOG), eq("PRESCRIPTION_DRAFT"),
                eq("PRESCRIPTION_DRAFT"), eq("CATALOG"), eq(outcome));
    }

    /**
     * Positive proof: a prescriber sees tagged, review-only alternatives for the
     * selected entry, sorted and provenance-bearing, with the search audited.
     */
    @Test
    void searchCatalogShowsTaggedReviewOnlyAlternativesForAPrescriber() {
        activeRole("clinician");
        when(repository.loadCatalog()).thenReturn(catalogWithAlternatives());

        List<CatalogHit> hits = service.searchCatalog(null, null);

        assertEquals(5, hits.size(), "unapproved, future and expired versions are not catalog hits");
        CatalogHit selected = hitFor(hits, SELECTED_ID);
        assertNotNull(selected);
        List<Alternative> alternatives = selected.alternatives();
        assertEquals(3, alternatives.size());
        assertEquals(List.of("AMOX-250", "SHARED-GROUP", "SHARED-SUB"),
                alternatives.stream().map(alternative -> alternative.entry().entryId()).toList(),
                "suggestions are deterministic and sorted by entry id");
        for (Alternative alternative : alternatives) {
            assertEquals(AlternativeKind.SUGGESTION, alternative.kind());
            assertEquals("suggestion", alternative.kind().wire());
            assertTrue(alternative.requiresClinicianSelection(),
                    "a suggestion never selects a medication for the prescriber");
            assertFalse(alternative.reason().isBlank(), "every suggestion carries a review reason");
            assertEquals(alternative.entry().approvalProvenance(), alternative.sourceCatalogVersion(),
                    "the source catalog version is the entry's approval provenance");
            assertNotEquals(SELECTED_ID, alternative.entry().entryId(),
                    "the selected entry is never offered as its own alternative");
        }
        assertEquals("same generic name", alternativeFor(alternatives, "AMOX-250").reason());
        assertEquals("shared ingredient identity", alternativeFor(alternatives, "SHARED-GROUP").reason());
        Alternative sharedSubstance = alternativeFor(alternatives, "SHARED-SUB");
        assertEquals("shared ingredient identity", sharedSubstance.reason());
        assertEquals(2, sharedSubstance.entry().version(), "only the newest effective version is suggested");
        assertEquals("synthetic-market-v2", sharedSubstance.sourceCatalogVersion());
        assertEquals(List.of(SELECTED_ID), hitFor(hits, "SHARED-SUB").alternatives().stream()
                .map(alternative -> alternative.entry().entryId()).toList(),
                "alternatives are computed per hit, not copied to every hit");
        assertTrue(hitFor(hits, "CIPRO").alternatives().isEmpty(),
                "an entry with no identity relation has no alternatives");
        verify(repository).loadCatalog();
        assertAudit("SUCCESS");
    }

    /** Negative proof of role: a receptionist cannot read the catalog or its alternatives. */
    @Test
    void searchCatalogRefusesASubjectWithoutCatalogPermission() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));

        ApiException denial = assertThrows(ApiException.class, () -> service.searchCatalog(null, null));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("missing RX_SEARCH_CATALOG permission", denial.getMessage(),
                "the denial must come from the role fold, not the membership fold");
        verify(repository).activeRoles(ACTOR);
        verify(repository, never()).loadCatalog();
        assertAudit("DENIED");
    }

    /** Negative proof of membership: an inactive subject cannot read the catalog. */
    @Test
    void searchCatalogRefusesAnInactiveSubject() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        ApiException denial = assertThrows(ApiException.class, () -> service.searchCatalog(null, null));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("no active membership", denial.getMessage(),
                "the denial must come from the membership fold before any role lookup");
        verify(repository, never()).loadCatalog();
        verify(repository, never()).activeRoles(ACTOR);
        assertAudit("DENIED");
    }

    /** Negative proof of version and approval windows: hidden versions are neither hits nor alternatives. */
    @Test
    void alternativesHideUnapprovedExpiredAndFutureVersions() {
        activeRole("clinician");
        when(repository.loadCatalog()).thenReturn(List.of(
                entry("ONLY-EFFECTIVE", 1, "Only", "Only", "tablet", "SUB", "GROUP", true, FROM, TO,
                        "synthetic-market-v1"),
                entry("UNAPPROVED", 1, "Only", "Only", "tablet", "SUB", "GROUP", false, FROM, TO,
                        "synthetic-market-v1"),
                entry("EXPIRED", 1, "Only", "Only", "tablet", "SUB", "GROUP", true, FROM, AT,
                        "synthetic-market-v1"),
                entry("FUTURE", 1, "Only", "Only", "tablet", "SUB", "GROUP", true, AT.plusSeconds(1), TO,
                        "synthetic-market-v2")));

        List<CatalogHit> hits = service.searchCatalog(null, null);

        assertEquals(1, hits.size(), "only the approved, in-window entry is visible");
        assertEquals("ONLY-EFFECTIVE", hits.get(0).entry().entryId());
        assertTrue(hits.get(0).alternatives().isEmpty(),
                "an unapproved, expired or future version is never shown for review");
        assertAudit("SUCCESS");
    }

    /** Fail-closed proof: an empty catalog yields no hits or alternatives through the service. */
    @Test
    void emptyCatalogShowsNoAlternatives() {
        activeRole("clinician");
        when(repository.loadCatalog()).thenReturn(List.of());

        assertTrue(service.searchCatalog(null, null).isEmpty(), "an empty catalog yields no hits");
        verify(repository).loadCatalog();
        assertAudit("SUCCESS");
    }

    /**
     * Fail-closed proof at the fold boundary: a missing selection invents no
     * alternatives. The service always passes an effective entry, so this
     * null-selected path can only be reached by calling the catalog directly.
     */
    @Test
    void missingSelectionFailsClosedWithoutInventingAlternatives() {
        assertTrue(new DrugCatalog(List.of()).alternativesFor(null, AT).isEmpty(),
                "a missing selection fails closed without inventing alternatives");
    }
}
