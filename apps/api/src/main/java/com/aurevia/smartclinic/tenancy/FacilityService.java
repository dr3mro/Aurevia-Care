package com.aurevia.smartclinic.tenancy;

import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.authz.ActionPolicy;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Enforces membership, permission and audit invariants inside one transaction.
 *
 * <p>Authorization is re-evaluated on every protected request so revocation is
 * effective on the next call without token or session expiry.</p>
 */
@Service
public class FacilityService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{2,32}");

    private final FacilityRepository repository;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded repository
     * @param policy       action policy
     * @param transactions tenant-guarded transaction template
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public FacilityService(FacilityRepository repository, ActionPolicy policy,
                           TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Read a facility in the active organization.
     *
     * @param code tenant-local facility code
     * @return the visible facility
     * @throws ApiException when authorization fails or the facility is not visible
     */
    public Facility get(String code) {
        return transactions.execute(status -> {
            authorize("FACILITY_READ");
            Facility facility = repository.findByCode(code)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            repository.appendAudit(TenantContext.current(), "FACILITY_READ", "FACILITY", code, "SUCCESS");
            return facility;
        });
    }

    /**
     * Create a facility in the active organization with the default UTC zone.
     *
     * @param code tenant-local facility code
     * @param name display name
     * @return the created facility
     * @throws ApiException on invalid input, denied access or a duplicate code
     */
    public Facility create(String code, String name) {
        return create(code, name, null);
    }

    /**
     * Create a facility in the active organization.
     *
     * <p>An absent time zone defaults to UTC so an existing client cannot start
     * sending wrong local wall-clock times through a half-populated record.</p>
     *
     * @param code     tenant-local facility code
     * @param name     display name
     * @param timeZone IANA time zone, or {@code null} for UTC
     * @return the created facility
     * @throws ApiException on invalid input, denied access or a duplicate code
     */
    public Facility create(String code, String name, String timeZone) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new ApiException(422, "INVALID_CODE", "code must be 2-32 uppercase letters, digits, - or _");
        }
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new ApiException(422, "INVALID_NAME", "name must be 1-200 characters");
        }
        String zone = timeZone == null ? "UTC" : timeZone;
        try {
            java.time.ZoneId.of(zone);
        } catch (java.time.DateTimeException exception) {
            throw new ApiException(422, "INVALID_TIME_ZONE", "timeZone must be an IANA zone");
        }
        return transactions.execute(status -> {
            authorize("FACILITY_WRITE");
            TenantContext context = TenantContext.current();
            Facility facility = new Facility(context.organizationId(), code, name, zone);
            try {
                repository.insert(facility);
            } catch (DuplicateKeyException exception) {
                throw new ApiException(409, "CONFLICT", "facility code already exists");
            }
            repository.appendAudit(context, "FACILITY_CREATE", "FACILITY", code, "SUCCESS");
            return facility;
        });
    }

    /**
     * Re-check membership and action permission inside the current transaction.
     *
     * @param action action key to authorize
     * @throws ApiException when membership or permission is missing
     */
    private void authorize(String action) {
        TenantContext context = TenantContext.current();
        if (!repository.hasActiveMembership(context.subject())) {
            throw new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            throw new ApiException(403, "FORBIDDEN", "missing action permission");
        }
    }
}
