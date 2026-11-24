package com.aurevia.tenancy;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.FacilityCode;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Enforces membership, permission and audit invariants inside one transaction.
 *
 * <p>Authorization is re-evaluated on every protected request so revocation is
 * effective on the next call without token or session expiry.</p>
 */
@Service
public class FacilityService {

    private static final Pattern CURSOR = Pattern.compile(
            "(" + FacilityCode.PATTERN + "):"
            + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

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
     * Read one cursor-paginated page of facilities in the active organization.
     *
     * <p>The single-record read action is reused: a caller who may read one
     * facility may list the same tenant's facilities. Validation happens before
     * any transaction and the read itself is audited inside the guarded
     * transaction.</p>
     *
     * @param cursor opaque {@code <code>:<id>} of the previous page's last row, or {@code null}
     * @param limit  page size, or {@code null} for the default of 50
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input or denied access
     */
    public FacilityPage list(String cursor, String limit) {
        FacilityCursor parsedCursor = parseCursor(cursor);
        int parsedLimit = parseLimit(limit);
        return transactions.execute(status -> {
            authorize("FACILITY_READ");
            List<FacilityRepository.FacilityRow> rows = repository.query(
                    parsedCursor == null ? null : parsedCursor.code(),
                    parsedCursor == null ? null : parsedCursor.id(), parsedLimit + 1);
            boolean hasMore = rows.size() > parsedLimit;
            List<FacilityRepository.FacilityRow> pageRows = hasMore ? rows.subList(0, parsedLimit) : rows;
            List<Facility> items = pageRows.stream().map(FacilityRepository.FacilityRow::facility).toList();
            String next = hasMore
                    ? pageRows.get(pageRows.size() - 1).facility().code() + ":"
                            + pageRows.get(pageRows.size() - 1).id()
                    : null;
            repository.appendAudit(TenantContext.current(), "FACILITY_READ", "FACILITY", "ALL", "SUCCESS");
            return new FacilityPage(items, next);
        });
    }

    private FacilityCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        Matcher matcher = CURSOR.matcher(cursor);
        if (!matcher.matches()) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be <code>:<id>");
        }
        return new FacilityCursor(matcher.group(1), matcher.group(2));
    }

    private int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit);
            if (value < 1 || value > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-200");
        }
    }

    /** Parsed opaque cursor: the facility code sort value and the immutable row id. */
    private record FacilityCursor(String code, String id) {
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
        try {
            FacilityCode.facilityCode(code);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, "INVALID_CODE", FacilityCode.INVALID_MESSAGE);
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
