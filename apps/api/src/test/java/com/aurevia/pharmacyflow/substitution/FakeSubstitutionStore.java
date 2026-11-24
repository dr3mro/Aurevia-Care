package com.aurevia.pharmacyflow.substitution;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.SignedRevision;
import com.aurevia.prescription.PrescriptionContract.Alternative;

/** Deterministic in-test decision store; mirrors the V94 uniqueness rules. */
class FakeSubstitutionStore implements SubstitutionStore {

    private final Map<String, Proposal> proposals = new HashMap<>();
    private final Map<String, SignedRevision> revisions = new HashMap<>();
    private final Map<String, List<Alternative>> alternatives = new HashMap<>();
    private final Map<String, String> claims = new HashMap<>();
    private final Map<String, Integer> revisionNumbers = new HashMap<>();

    @Override
    public Proposal findProposal(String organizationId, String proposalId) {
        Proposal proposal = proposals.get(proposalId);
        return proposal != null && proposal.organizationId().equals(organizationId) ? proposal : null;
    }

    @Override
    public List<Alternative> findAlternatives(String organizationId, String proposalId) {
        return alternatives.getOrDefault(proposalId, List.of());
    }

    @Override
    public SignedRevision findRevision(String organizationId, String proposalId) {
        return revisions.get(proposalId);
    }

    @Override
    public String findReplayProposalId(String organizationId, String command, String idempotencyKey) {
        return claims.get(claimKey(organizationId, command, idempotencyKey));
    }

    @Override
    public boolean claim(String organizationId, String command, String idempotencyKey, String proposalId) {
        String key = claimKey(organizationId, command, idempotencyKey);
        if (claims.containsKey(key)) {
            return false;
        }
        claims.put(key, proposalId);
        return true;
    }

    @Override
    public void insertProposal(Proposal proposal, List<Alternative> frozenAlternatives) {
        proposals.put(proposal.id(), proposal);
        alternatives.put(proposal.id(), List.copyOf(frozenAlternatives));
    }

    @Override
    public void insertRevision(String organizationId, SignedRevision revision) {
        revisions.put(revision.proposalId(), revision);
    }

    @Override
    public void closeProposal(Proposal closed, Instant decidedAt) {
        proposals.put(closed.id(), closed);
    }

    @Override
    public int nextRevisionNumber(String organizationId, String prescriptionId) {
        return revisionNumbers.merge(prescriptionId, 1, Integer::sum);
    }

    private static String claimKey(String organizationId, String command, String idempotencyKey) {
        return organizationId + "|" + command + "|" + idempotencyKey;
    }
}
