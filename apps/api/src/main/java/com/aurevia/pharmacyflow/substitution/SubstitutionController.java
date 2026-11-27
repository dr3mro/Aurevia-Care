package com.aurevia.pharmacyflow.substitution;

import java.util.Set;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.pharmacyflow.substitution.SubstitutionCommands.DecideCommand;
import com.aurevia.pharmacyflow.substitution.SubstitutionCommands.ProposeCommand;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.ProposalView;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for pharmacy substitution proposals.
 *
 * <p>This controller only binds paths, headers and command bodies. Tenant and
 * actor come from the request context and active roles from freshly resolved
 * membership. Every command is delegated to the injected domain service; an
 * {@code Idempotency-Key} is required on both writes and an {@code If-Match}
 * version on the prescriber's decision.</p>
 */
@RestController
@RequestMapping("/api/v1/pharmacy/substitutions")
public final class SubstitutionController {

    private final SubstitutionService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route over the injected substitution kernel.
     *
     * @param service      substitution domain service
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded template for the role fold
     */
    public SubstitutionController(SubstitutionService service, StaffRepository staff,
                                   TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Propose a tagged catalog alternative for the prescription's medication.
     *
     * @param prescriptionId prescription identifier from the path
     * @param idem           retry key for this action
     * @param request        proposal body carrying entries, reason and optional note
     * @return the pending proposal with side-by-side entries
     */
    @PostMapping("/{prescriptionId}/proposals")
    public ProposalView propose(@PathVariable String prescriptionId,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                @RequestBody ProposeRequest request) {
        TenantContext context = context();
        return service.propose(new ProposeCommand(context.organizationId(), context.subject(),
                activeRoles(context), prescriptionId, request.currentEntryId(), request.proposedEntryId(),
                request.reason(), request.note(), request.overrideReason(), key(idem)));
    }

    /**
     * Approve a pending proposal, creating its signed revision.
     *
     * @param proposalId proposal identifier from the path
     * @param ifMatch    expected proposal version
     * @param idem       retry key for this action
     * @param request    optional decision note
     * @return the approved proposal and its signed revision
     */
    @PostMapping("/{proposalId}/approve")
    public ProposalView approve(@PathVariable String proposalId,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                @RequestBody DecideRequest request) {
        return decide(proposalId, ifMatch, idem, request.attestation(), request.note(), true);
    }

    /**
     * Decline a pending proposal, keeping the original medication.
     *
     * @param proposalId proposal identifier from the path
     * @param ifMatch    expected proposal version
     * @param idem       retry key for this action
     * @param request    optional decision note
     * @return the declined proposal without a revision
     */
    @PostMapping("/{proposalId}/decline")
    public ProposalView decline(@PathVariable String proposalId,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                @RequestBody DecideRequest request) {
        return decide(proposalId, ifMatch, idem, request.attestation(), request.note(), false);
    }

    /**
     * Read one proposal visible to one of its two parties.
     *
     * @param proposalId proposal identifier from the path
     * @return the proposal with its side-by-side entries and optional revision
     */
    @GetMapping("/{proposalId}")
    public ProposalView view(@PathVariable String proposalId) {
        TenantContext context = context();
        return service.view(context.organizationId(), proposalId, context.subject(), activeRoles(context));
    }

    private ProposalView decide(String proposalId, String ifMatch, String idem, String attestation,
                                String note, boolean approved) {
        TenantContext context = context();
        return service.decide(new DecideCommand(context.organizationId(), context.subject(),
                activeRoles(context), proposalId, IfMatch.parse(ifMatch), approved, attestation, note, key(idem)));
    }

    private static String key(String header) {
        return IdempotencyKey.parse(header).value();
    }

    private Set<String> activeRoles(TenantContext context) {
        // The authoritative role fold is RLS-bounded, so it needs the tenant GUC
        // that the guarded transaction binds before the query runs.
        return Set.copyOf(transactions.execute(status -> staff.activeRoles(context.subject())));
    }

    private static TenantContext context() {
        return TenantContext.current();
    }

    /**
     * Request body for proposing a tagged catalog alternative.
     *
     * @return this immutable body
     */
    public record ProposeRequest(String currentEntryId, String proposedEntryId, String reason, String note,
                                 String overrideReason) {
    }

    /**
     * Request body for a prescriber decision with an attestation and optional note.
     *
     * @return this immutable body
     */
    public record DecideRequest(String attestation, String note) {
    }
}
