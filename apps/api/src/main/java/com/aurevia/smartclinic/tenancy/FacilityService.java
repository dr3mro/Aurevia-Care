package com.aurevia.smartclinic.tenancy;

import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.authz.ActionPolicy;
import com.aurevia.smartclinic.tenant.TenantContext;

/** Enforces membership, permission and audit invariants inside one transaction. */
@Service
public class FacilityService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{2,32}");

    private final FacilityRepository repository;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public FacilityService(FacilityRepository repository, ActionPolicy policy,
                           TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    public Facility get(String code) {
        return transactions.execute(status -> {
            authorize("FACILITY_READ");
            Facility facility = repository.findByCode(code)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            repository.appendAudit(TenantContext.current(), "FACILITY_READ", "FACILITY", code, "SUCCESS");
            return facility;
        });
    }

    public Facility create(String code, String name) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new ApiException(422, "INVALID_CODE", "code must be 2-32 uppercase letters, digits, - or _");
        }
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new ApiException(422, "INVALID_NAME", "name must be 1-200 characters");
        }
        return transactions.execute(status -> {
            authorize("FACILITY_WRITE");
            TenantContext context = TenantContext.current();
            Facility facility = new Facility(context.organizationId(), code, name);
            try {
                repository.insert(facility);
            } catch (DuplicateKeyException exception) {
                throw new ApiException(409, "CONFLICT", "facility code already exists");
            }
            repository.appendAudit(context, "FACILITY_CREATE", "FACILITY", code, "SUCCESS");
            return facility;
        });
    }

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
