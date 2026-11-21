package com.aurevia.smartclinic.staffmsg.mail;

import java.util.List;

import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staff.StaffRepository.StaffRow;

/**
 * Production recipient directory backed by the authoritative staff projection.
 *
 * <p>The lookup runs inside the tenant-guarded transaction so the forced RLS
 * policy sees {@code app.current_org} and only active members of the active
 * organization are visible. A role is resolved from the effective role grants
 * of those members; the facility is carried on the recipient for the audit
 * record and facility-scoped directories can refine it later.</p>
 */
final class StaffRecipientDirectory implements RecipientDirectory {

    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the directory over the shared staff repository.
     *
     * @param staff        authoritative staff membership and role source
     * @param transactions tenant-guarded transaction template
     */
    StaffRecipientDirectory(StaffRepository staff, TransactionTemplate transactions) {
        this.staff = staff;
        this.transactions = transactions;
    }

    @Override
    public List<String> holders(String organizationId, String facilityId, String role) {
        return transactions.execute(status -> staff
                .query(null, null, "active", Integer.MAX_VALUE).stream()
                .filter(row -> row.member().roles().contains(role))
                .map(StaffRow::member)
                .map(member -> member.subject())
                .distinct()
                .toList());
    }
}
