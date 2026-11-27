package com.aurevia.prescription;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.catalog.MedicationCatalog;
import com.aurevia.catalog.MedicationCatalogVersion;
import com.aurevia.catalog.VersionedCatalogSearch;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.AllergyCheckRequest;
import com.aurevia.prescription.PrescriptionContract.CatalogHit;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.prescription.PrescriptionContract.CreateDraftRequest;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DraftView;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.SelectMedicationRequest;
import com.aurevia.tenant.TenantContext;

/**
 * Prescription-draft workflow.
 *
 * <p>Every command runs in one tenant-guarded transaction: authorization is
 * re-derived from the database folds, the version precondition is checked on a
 * locked row, the command and its audit are committed together, and an override
 * is an immutable fact bound to the exact draft revision and matched data.</p>
 */
@Service
public class PrescriptionService {

    private static final String SCOPE = "PRESCRIPTION_DRAFT";
    private static final String RESOURCE_TYPE = "PRESCRIPTION_DRAFT";

    private final PrescriptionRepository repository;
    private final PrescriptionActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded prescription repository
     * @param actions      bounded action matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        application clock for catalog effective windows
     */
    public PrescriptionService(PrescriptionRepository repository, PrescriptionActions actions,
                               TransactionTemplate transactions, AuditAppender audit, Clock clock) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Create a draft for a patient visible in the active tenant.
     *
     * @param request creation command
     * @return the created draft
     * @throws ApiException on invalid input, denial or an invisible patient
     */
    public Draft createDraft(CreateDraftRequest request) {
        String patientId = PrescriptionInputs.uuid(request.patientId(), "patientId");
        String encounterReference = PrescriptionInputs.optionalText(request.encounterReference(),
                "encounterReference", 128);
        String destination = PrescriptionInputs.optionalText(request.destination(), "destination", 128);
        String id = PrescriptionInputs.newId();
        return execute(PrescriptionActions.CREATE_DRAFT, id, sink -> {
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            repository.insertDraft(id, patientId, encounterReference, destination);
            success(PrescriptionActions.CREATE_DRAFT, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Search the approved effective catalog and attach tagged suggestions.
     *
     * @param query       name fragment, or {@code null}/blank for all
     * @param formulation exact formulation, or {@code null}/blank for any
     * @return effective entries with their alternatives
     * @throws ApiException on denial or invalid input
     */
    public List<CatalogHit> searchCatalog(String query, String formulation) {
        String name = PrescriptionInputs.optionalText(query, "query", 128);
        String form = PrescriptionInputs.optionalText(formulation, "formulation", 64);
        return execute(PrescriptionActions.SEARCH_CATALOG, "CATALOG", sink -> {
            DrugCatalog catalog = new DrugCatalog(repository.loadCatalog());
            List<CatalogHit> hits = new ArrayList<>();
            for (DrugEntry entry : catalog.search(name, form, clock.instant())) {
                hits.add(new CatalogHit(entry, catalog.alternativesFor(entry, clock.instant())));
            }
            success(PrescriptionActions.SEARCH_CATALOG, "CATALOG");
            return hits;
        });
    }

    /**
     * Search the approved effective catalog and return the published version used.
     *
     * <p>The catalog version is read in the same tenant-guarded transaction as the
     * hits, so a publication between two searches is observed as a version change
     * rather than a silently rewritten result.</p>
     *
     * @param query       name fragment, or {@code null}/blank for all
     * @param formulation exact formulation, or {@code null}/blank for any
     * @return effective items tagged with the catalog version they came from
     * @throws ApiException on denial or invalid input
     */
    public VersionedCatalogSearch searchCatalogWithVersion(String query, String formulation) {
        String name = PrescriptionInputs.optionalText(query, "query", 128);
        String form = PrescriptionInputs.optionalText(formulation, "formulation", 64);
        return execute(PrescriptionActions.SEARCH_CATALOG, "CATALOG", sink -> {
            MedicationCatalogVersion version = repository.currentCatalogVersion();
            MedicationCatalog catalog = new MedicationCatalog(repository.loadMedicationCatalog(), version);
            VersionedCatalogSearch result = catalog.search(name, form, clock.instant());
            success(PrescriptionActions.SEARCH_CATALOG, "CATALOG");
            return result;
        });
    }

    /**
     * Read the catalog version a draft was built from.
     *
     * @param id draft identifier
     * @return the immutable draft-to-catalog binding
     * @throws ApiException on denial, invalid input or an invisible draft
     */
    public DraftCatalogVersion catalogVersion(String id) {
        PrescriptionInputs.uuid(id, "id");
        return execute(PrescriptionActions.READ_DRAFT, id, sink -> {
            Draft draft = repository.find(id).orElseThrow(notFound());
            DraftCatalogVersion binding = new DraftCatalogVersion(draft.id(), repository.catalogVersion(id));
            success(PrescriptionActions.READ_DRAFT, id);
            return binding;
        });
    }

    /**
     * Check a candidate drug against the patient's verified allergy facts.
     *
     * @param id      draft identifier
     * @param request check command
     * @return the immutable, versioned check result
     * @throws ApiException on denial, invalid input or an invisible draft
     */
    public AllergyCheck checkAllergies(String id, AllergyCheckRequest request) {
        PrescriptionInputs.uuid(id, "id");
        String entryId = PrescriptionInputs.uuid(request.catalogEntryId(), "catalogEntryId");
        return execute(PrescriptionActions.CHECK_ALLERGIES, id, sink -> {
            Draft draft = repository.find(id).orElseThrow(notFound());
            DrugEntry entry = requireEntry(entryId);
            AllergyCheck check = AllergyChecker.check(entry, repository.allergyAssessment(draft.patientId()));
            success(PrescriptionActions.CHECK_ALLERGIES, id);
            return check;
        });
    }

    /**
     * Select a medication with an {@code If-Match} precondition, recording a
     * documented override when a verified conflict was reviewed.
     *
     * @param id              draft identifier
     * @param request         selection command
     * @param expectedVersion version read by the caller
     * @return the updated draft
     * @throws ApiException on denial, stale version, invalid input or an unreviewed conflict
     */
    public Draft selectMedication(String id, SelectMedicationRequest request, int expectedVersion) {
        PrescriptionInputs.uuid(id, "id");
        String entryId = PrescriptionInputs.uuid(request.catalogEntryId(), "catalogEntryId");
        String doseValue = PrescriptionInputs.text(request.doseValue(), "doseValue", 32);
        String doseUnit = PrescriptionInputs.text(request.doseUnit(), "doseUnit", 32);
        String route = PrescriptionInputs.text(request.route(), "route", 64);
        String frequency = PrescriptionInputs.text(request.frequency(), "frequency", 64);
        String quantity = PrescriptionInputs.text(request.quantity(), "quantity", 32);
        String quantityUnit = PrescriptionInputs.text(request.quantityUnit(), "quantityUnit", 32);
        Integer refills = PrescriptionInputs.refills(request.refills());
        String instructions = PrescriptionInputs.text(request.instructions(), "instructions", 1000);
        String overrideReason = PrescriptionInputs.optionalText(request.overrideReason(), "overrideReason", 1000);
        return execute(PrescriptionActions.SELECT_MEDICATION, id, sink -> {
            Draft current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            int catalogVersion = repository.currentCatalogVersion().version();
            DrugEntry entry = requireEntry(entryId);
            requireApprovedRoute(entry, route);
            AllergyCheck check = AllergyChecker.check(entry, repository.allergyAssessment(current.patientId()));
            if (check.outcome() == CheckOutcome.CONFLICT && overrideReason == null) {
                conflict(sink, PrescriptionActions.SELECT_MEDICATION, id, 409,
                        "ALLERGY_OVERRIDE_REQUIRED",
                        "a verified exact allergy match needs a documented override reason");
                return null;
            }
            if (overrideReason != null) {
                repository.insertOverride(id, expectedVersion, overrideReason, snapshot(entry, check));
            }
            repository.selectMedication(id, expectedVersion, entry.entryId(), entry.version(),
                    entry.genericName(), doseValue, doseUnit, route, frequency, quantity,
                    quantityUnit, refills, instructions, catalogVersion);
            success(PrescriptionActions.SELECT_MEDICATION, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Read a draft with its immutable override.
     *
     * @param id draft identifier
     * @return the draft view
     * @throws ApiException on denial or an invisible draft
     */
    public DraftView get(String id) {
        PrescriptionInputs.uuid(id, "id");
        return execute(PrescriptionActions.READ_DRAFT, id, sink -> {
            Draft draft = repository.find(id).orElseThrow(notFound());
            DraftView view = new DraftView(draft, repository.findOverride(id).orElse(null));
            success(PrescriptionActions.READ_DRAFT, id);
            return view;
        });
    }

    /**
     * Reject a route that contradicts the market-approved catalog route.
     *
     * <p>Legacy entries carry the sentinel {@code unspecified} because they were
     * recorded before the route column existed; they cannot be checked here and
     * remain subject to the surrounding clinical workflow. A declared route is
     * authoritative: a wrong-route prescription is a medication safety error.</p>
     *
     * @param entry          the approved catalog entry being selected
     * @param requestedRoute route entered by the prescriber
     * @throws ApiException when the requested route contradicts the catalog
     */
    private void requireApprovedRoute(DrugEntry entry, String requestedRoute) {
        String approvedRoute = repository.catalogRoute(entry.entryId(), entry.version());
        if (!"unspecified".equals(approvedRoute)
                && !approvedRoute.equalsIgnoreCase(requestedRoute)) {
            throw new ApiException(422, "ROUTE_MISMATCH",
                    "the requested route does not match the market-approved catalog route");
        }
    }

    private DrugEntry requireEntry(String entryId) {
        DrugCatalog catalog = new DrugCatalog(repository.loadCatalog());
        DrugEntry entry = catalog.effectiveEntry(entryId, clock.instant());
        if (entry == null) {
            throw new ApiException(422, "INVALID_CATALOG_ENTRY", "no approved effective drug catalog entry");
        }
        return entry;
    }

    private String snapshot(DrugEntry entry, AllergyCheck check) {
        String findings = check.findings().stream()
                .map(finding -> finding.kind().wire() + ":"
                        + String.valueOf(finding.substanceId()) + ":"
                        + String.valueOf(finding.group()))
                .sorted().collect(Collectors.joining(","));
        return "catalog=" + entry.entryId() + ":" + entry.version()
                + ";allergy=" + check.allergyStatus().wire()
                + ";outcome=" + check.outcome().wire()
                + ";findings=" + findings;
    }

    private <T> T execute(String action, String resourceId, Function<Sink, T> work) {
        Sink sink = new Sink();
        T value = transactions.execute(status -> {
            TenantContext context = context();
            ApiException denial = authorize(context, action, resourceId);
            if (denial != null) {
                sink.failure = denial;
                return null;
            }
            try {
                return work.apply(sink);
            } catch (ApiException refusal) {
                // Preconditions raise before any row mutation, so the only
                // committed write on this refusal is the DENIED audit event.
                // Carry the original failure out on the sink: throwing here
                // would roll the audit row back with the failed command.
                audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
                sink.failure = refusal;
                return null;
            }
        });
        if (sink.failure != null) {
            throw sink.failure;
        }
        return value;
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> actions.allows(action, role))) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing " + action + " permission");
        }
        return null;
    }

    private void requireVersion(Draft current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the prescription draft changed since it was read");
        }
    }

    private void success(String action, String resourceId) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "SUCCESS");
    }

    private void conflict(Sink sink, String action, String resourceId, int status, String code,
                          String message) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "CONFLICT");
        sink.failure = new ApiException(status, code, message);
    }

    private TenantContext context() {
        return TenantContext.current();
    }

    private Supplier<ApiException> notFound() {
        return () -> new ApiException(404, "NOT_FOUND", "prescription draft not found");
    }

    /**
     * Carries a durable failure out of a committed transaction so its audit and
     * conflict record survive while the caller still receives an error.
     */
    private static final class Sink {
        private ApiException failure;
    }
}
