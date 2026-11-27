package com.aurevia.pharmacyflow.substitution;

import java.time.Instant;
import java.util.List;

import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.SignedRevision;
import com.aurevia.prescription.PrescriptionContract.Alternative;

/**
 * Durable persistence boundary for pharmacy substitution decisions.
 *
 * <p>Every method runs inside the caller's tenant-guarded transaction, so forced
 * row-level security and the transaction-local organization binding confine each
 * statement to the active tenant. Writes carry the organization explicitly as a
 * second line of defence when the policy's check expression is evaluated. An
 * idempotency claim is a compare-and-set: its boolean answer says whether this
 * caller owns the one and only decision for a key, never merely whether a row
 * happens to exist.</p>
 */
public interface SubstitutionStore {

    /**
     * Read a proposal visible in the active tenant.
     *
     * @param organizationId active tenant
     * @param proposalId     tenant-local proposal identifier
     * @return the persisted proposal, or {@code null} when it is not visible
     */
    Proposal findProposal(String organizationId, String proposalId);

    /**
     * Read the tagged alternatives frozen with a proposal.
     *
     * @param organizationId active tenant
     * @param proposalId     owning proposal
     * @return the immutable alternatives shown at proposal time
     */
    List<Alternative> findAlternatives(String organizationId, String proposalId);

    /**
     * Read the signed revision applied by an approved proposal.
     *
     * @param organizationId active tenant
     * @param proposalId     owning proposal
     * @return the persisted revision, or {@code null} when the proposal was declined
     */
    SignedRevision findRevision(String organizationId, String proposalId);

    /**
     * Find the proposal already decided under an idempotency key.
     *
     * @param organizationId active tenant
     * @param command        command namespace, so approve and decline cannot collide
     * @param idempotencyKey composed retry key
     * @return the winning proposal identifier, or {@code null} when the key is new
     */
    String findReplayProposalId(String organizationId, String command, String idempotencyKey);

    /**
     * Claim an idempotency key atomically for one proposal.
     *
     * @param organizationId active tenant
     * @param command        command namespace
     * @param idempotencyKey composed retry key
     * @param proposalId     proposal this claim binds to
     * @return {@code true} when this caller inserted the claim, {@code false} on replay
     */
    boolean claim(String organizationId, String command, String idempotencyKey, String proposalId);

    /**
     * Insert a pending proposal and its frozen alternatives.
     *
     * @param proposal     the pending proposal
     * @param alternatives tagged alternatives shown side by side
     */
    void insertProposal(Proposal proposal, List<Alternative> alternatives);

    /**
     * Append the immutable signed revision of an approval.
     *
     * @param organizationId active tenant
     * @param revision       signed revision with full provenance
     */
    void insertRevision(String organizationId, SignedRevision revision);

    /**
     * Close a pending proposal with its one decision.
     *
     * @param closed    the approved or declined proposal
     * @param decidedAt decision instant
     */
    void closeProposal(Proposal closed, Instant decidedAt);

    /**
     * Allocate the next durable revision number for a prescription.
     *
     * @param organizationId active tenant
     * @param prescriptionId owning prescription
     * @return one plus the number of revisions already recorded
     */
    int nextRevisionNumber(String organizationId, String prescriptionId);
}
