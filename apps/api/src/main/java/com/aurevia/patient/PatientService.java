package com.aurevia.patient;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.IssuerCode;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Patient registration invariants (IAM-03).
 *
 * <p>Names are normalized only for search; original spelling is preserved.
 * A likely-duplicate warning is computed from normalized name and date of birth
 * and never merges or links records automatically.</p>
 */
@Service
public class PatientService {

    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{M} '.-]{2,120}");
    private static final Pattern MRN = Pattern.compile("[A-Za-z0-9_.:-]{2,64}");
    private static final Pattern REASON = Pattern.compile("[a-z_]{2,32}");

    private final PatientRepository repository;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded patient repository
     * @param policy       action policy
     * @param transactions tenant-guarded transaction template
     */
    public PatientService(PatientRepository repository, ActionPolicy policy, TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Register a new patient with an issuer-scoped MRN.
     *
     * @param displayName     original name spelling
     * @param mrn             issuer-scoped MRN
     * @param issuer          MRN issuer
     * @param dob             civil date of birth, or {@code null}
     * @param dobUnknownReason coded reason when DOB is unknown
     * @param language        patient language
     * @param contacts        contact records as JSON text
     * @param identifiers     identity records as JSON text
     * @param consents        consent records as JSON text
     * @return the registered patient
     * @throws ApiException on invalid input, denied access or a duplicate MRN
     */
    public Patient register(String displayName, String mrn, String issuer, String dob,
                            String dobUnknownReason, String language, String contacts,
                            String identifiers, String consents) {
        validateName(displayName);
        if (mrn == null || !MRN.matcher(mrn).matches()) {
            throw new ApiException(422, "INVALID_MRN", "mrn must be 2-64 letters, digits or _ . : -");
        }
        try {
            IssuerCode.issuerCode(issuer);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, "INVALID_ISSUER", "issuer must be 2-32 letters, digits or _ . : -");
        }
        String date = validateDob(dob, dobUnknownReason);
        String id = UUID.randomUUID().toString();
        String normalized = normalize(displayName);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Patient patient = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "PATIENT_REGISTER", mrn);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.mrnExists(issuer, mrn)) {
                repository.appendAudit(context, "PATIENT_REGISTER", mrn, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "MRN already exists for this issuer"));
                return null;
            }
            try {
                repository.insert(context.organizationId(), id, mrn, issuer, displayName, normalized,
                        date, dobUnknownReason, language,
                        contacts == null ? "{}" : contacts,
                        identifiers == null ? "[]" : identifiers,
                        consents == null ? "[]" : consents);
            } catch (DuplicateKeyException exception) {
                // A concurrent registration can still win the unique index; the
                // transaction is aborted, so the conflict is reported without an
                // audit write (the caller's failed statement is already recorded).
                failure.set(new ApiException(409, "CONFLICT", "MRN already exists for this issuer"));
                return null;
            }
            repository.appendAudit(context, "PATIENT_REGISTER", id, "SUCCESS");
            return repository.findById(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return patient;
    }

    /**
     * Read an effective patient by identifier, with likely-duplicate warnings.
     *
     * @param id patient identifier
     * @return the patient and its possible duplicates
     * @throws ApiException on invalid input, denial or an unknown patient
     */
    public PatientView get(String id) {
        if (id == null || !MRN.matcher(id).matches()) {
            throw new ApiException(422, "INVALID_ID", "id must be a UUID or bounded identifier");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        PatientView view = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "PATIENT_READ", id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Patient patient = repository.findById(id).orElse(null);
            if (patient == null) {
                repository.appendAudit(context, "PATIENT_READ", id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            List<Patient> duplicates = patient.dob() == null ? List.of()
                    : repository.likelyDuplicates(patient.normalizedName(), patient.dob(), patient.id());
            repository.appendAudit(context, "PATIENT_READ", id, "SUCCESS");
            return new PatientView(patient, duplicates);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view;
    }

    /**
     * Search effective patients by name, preserving original spelling in results.
     *
     * @param name display name or normalized prefix
     * @return matching patients
     * @throws ApiException on invalid input or denial
     */
    public List<Patient> search(String name) {
        validateName(name);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<Patient> results = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "PATIENT_READ", "SEARCH");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<Patient> found = repository.search(normalize(name));
            repository.appendAudit(context, "PATIENT_SEARCH", "SEARCH", "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return results;
    }

    /**
     * Normalize a name for search only, stripping case and diacritics.
     *
     * @param name original name
     * @return normalized search form
     */
    static String normalize(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String stripped = decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return stripped.replaceAll("[^\\p{L}0-9]+", " ").trim();
    }

    private String validateDob(String dob, String reason) {
        if (dob == null || dob.isBlank()) {
            if (reason == null || !REASON.matcher(reason).matches()) {
                throw new ApiException(422, "INVALID_DOB", "a DOB or coded unknown reason is required");
            }
            return null;
        }
        if (reason != null) {
            throw new ApiException(422, "INVALID_DOB", "DOB and unknown reason are mutually exclusive");
        }
        try {
            return LocalDate.parse(dob).toString();
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_DOB", "dob must be an ISO civil date");
        }
    }

    private void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ApiException(422, "INVALID_NAME", "name must be 2-120 letters, spaces or ' . -");
        }
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            repository.appendAudit(context, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing patient permission");
        }
        return null;
    }
}
