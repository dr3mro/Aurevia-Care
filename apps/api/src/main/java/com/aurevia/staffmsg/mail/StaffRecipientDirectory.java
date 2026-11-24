package com.aurevia.staffmsg.mail;

import java.util.List;

import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staff.StaffRepository.StaffRow;
import com.aurevia.staffmsg.directory.DirectoryPolicy;
import com.aurevia.staffmsg.directory.StaffDirectoryService;
import com.aurevia.tenancy.Facility;
import com.aurevia.tenant.TenantContext;

/**
 * Facility-scoped production recipient directory backed by the authoritative staff projection.
 *
 * <p>A draft carries the facility row id. It is resolved against the tenant-guarded
 * facility directory inside the same transaction as the active-staff read, so forced
 * row-level security confines every lookup to the active organization. A role holder
 * qualifies only when the authoritative membership-facility fold places that holder in
 * the resolved facility; the fold is keyed by facility code, not by the draft id.</p>
 *
 * <p>The fold deliberately preserves the V93 rule that a member with no recorded
 * assignment keeps organization-wide scope, and that oversight roles are exempt. Mail
 * reuses that decision instead of defining a second one. Missing context, an unknown
 * facility or a facility from another organization all fail closed before recipients
 * are resolved.</p>
 */
final class StaffRecipientDirectory implements RecipientDirectory {

    private static final String ACTIVE = "active";

    private final StaffRepository staff;
    private final TransactionTemplate transactions;
    private final StaffDirectoryService directory;
    private final AccessRepository access;
    private final DirectoryPolicy policy;

    /**
     * Create the directory over the shared tenant-scoped collaborators.
     *
     * @param staff        authoritative staff membership and role source
     * @param transactions tenant-guarded transaction template
     * @param directory    tenant-scoped facility resolver, including resolution by id
     * @param access       authoritative facility-scope fold boundary
     * @param policy       same-organization authorization rule
     */
    StaffRecipientDirectory(StaffRepository staff, TransactionTemplate transactions,
                            StaffDirectoryService directory, AccessRepository access, DirectoryPolicy policy) {
        this.staff = staff;
        this.transactions = transactions;
        this.directory = directory;
        this.access = access;
        this.policy = policy;
    }

    @Override
    public List<String> holders(String organizationId, String facilityId, String role) {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "MISSING_TENANT_CONTEXT", "an authenticated tenant context is required");
        }
        return transactions.execute(status -> {
            Facility facility = directory.findFacilityById(facilityId).orElseThrow(() ->
                    new ApiException(404, "FACILITY_NOT_FOUND", "facility is not in the active organization"));
            // The bound context, never the supplied organization argument, owns the
            // tenant; the facility organization comes from the RLS-guarded lookup.
            policy.requireSameOrganization(context.organizationId(), facility.organizationId());
            return staff.query(null, null, ACTIVE, Integer.MAX_VALUE).stream()
                    .filter(row -> row.member().roles().contains(role))
                    .filter(row -> !access.outsideAssignedFacilities(
                            row.member().subject(), facility.code()))
                    .map(StaffRow::member)
                    .map(member -> member.subject())
                    .distinct()
                    .toList();
        });
    }
}
