package com.aurevia.smartclinic.staffmsg.directory;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staff.StaffMember;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staff.StaffRepository.StaffRow;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.FacilityDirectory;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.FacilityEntry;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.StaffDirectory;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.StaffEntry;
import com.aurevia.smartclinic.tenancy.Facility;
import com.aurevia.smartclinic.tenancy.FacilityRepository;
import com.aurevia.smartclinic.tenancy.FacilityRepository.FacilityRow;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Tenant-scoped directory of facilities and staff who may be messaged (MSG-02, ARC-07).
 *
 * <p>Every read runs inside the tenant-guarded transaction so forced row-level
 * security confines results to the active organization. The organization and actor
 * come only from the bound context; no request field can select another tenant.
 * A missing context fails closed before any database access.</p>
 */
@Service
public class StaffDirectoryService {

    private static final String ACTIVE = "active";

    private final StaffRepository staff;
    private final FacilityRepository facilities;
    private final TransactionTemplate transactions;
    private final ActiveStaffGuard gate;

    /**
     * Create the directory over the existing authoritative repositories.
     *
     * @param staff        authoritative staff membership and role source
     * @param facilities   authoritative tenant facility source
     * @param transactions tenant-guarded transaction template
     * @param gate         active-staff authorization rule
     */
    public StaffDirectoryService(StaffRepository staff, FacilityRepository facilities,
                                 TransactionTemplate transactions, ActiveStaffGuard gate) {
        this.staff = staff;
        this.facilities = facilities;
        this.transactions = transactions;
        this.gate = gate;
    }

    /**
     * Resolve a facility by its tenant-local code in the active organization.
     *
     * @param facilityCode tenant-local facility code
     * @return the facility when visible, otherwise empty so callers fail closed
     */
    public Optional<Facility> findFacility(String facilityCode) {
        requireContext();
        return transactions.execute(status -> facilities.findByCode(facilityCode));
    }

    /**
     * Resolve a facility by the tenant-local identifier carried on a mail draft.
     *
     * <p>The browse API selects facilities by code, but stored drafts carry the
     * facility row id. This lookup uses the same forced-RLS repository and the same
     * context guard as {@link com.aurevia.smartclinic.staffmsg.directory.StaffDirectoryService#findFacility(String)}:
     * an id outside the active organization is invisible and fails closed.</p>
     *
     * @param facilityId tenant-local facility identifier, possibly null or unknown
     * @return the facility when visible, otherwise empty so callers fail closed
     */
    public Optional<Facility> findFacilityById(String facilityId) {
        requireContext();
        return transactions.execute(status -> facilities.query(null, null, Integer.MAX_VALUE).stream()
                .filter(row -> Objects.equals(row.id(), facilityId))
                .map(FacilityRow::facility)
                .findFirst());
    }

    /**
     * List the facilities visible to the actor.
     *
     * @return immutable facility directory in stable code order
     */
    public FacilityDirectory facilities() {
        TenantContext context = requireContext();
        return transactions.execute(status -> {
            gate.require(context);
            return new FacilityDirectory(facilities.query(null, null, Integer.MAX_VALUE).stream()
                    .map(FacilityRow::facility)
                    .map(FacilityEntry::from)
                    .toList());
        });
    }

    /**
     * List the staff who may be messaged in one facility of the active organization.
     *
     * @param facilityCode tenant-local facility code being browsed
     * @return immutable directory of active staff with at least one active role
     * @throws ApiException with status 404 when the facility is not in the active organization
     */
    public StaffDirectory staff(String facilityCode) {
        TenantContext context = requireContext();
        return transactions.execute(status -> {
            gate.require(context);
            Facility facility = facilities.findByCode(facilityCode).orElseThrow(() ->
                    new ApiException(404, "FACILITY_NOT_FOUND", "facility is not in the active organization"));
            return new StaffDirectory(staff.query(null, null, ACTIVE, Integer.MAX_VALUE).stream()
                    .map(StaffRow::member)
                    .filter(this::messagable)
                    .map(member -> StaffEntry.from(member, facility.code()))
                    .toList());
        });
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "MISSING_TENANT_CONTEXT", "an authenticated tenant context is required");
        }
        return context;
    }

    private boolean messagable(StaffMember member) {
        return !member.roles().isEmpty() && ACTIVE.equals(member.status());
    }
}
