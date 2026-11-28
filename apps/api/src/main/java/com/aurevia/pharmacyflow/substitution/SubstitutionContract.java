package com.aurevia.pharmacyflow.substitution;

import java.time.Instant;
import java.util.List;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for a pharmacy-proposed substitution.
 *
 * <p>A proposal is a tagged catalog alternative offered by the pharmacy with a
 * reason and the allergy assessment it was checked against. It is review-only
 * until the named prescriber approves it; approval produces an immutable signed
 * revision that carries its provenance and the prescriber's attestation, while a
 * decline leaves the originally prescribed medication untouched. The types
 * carry no transport or persistence concern and reuse the versioned drug
 * catalog entries and tagged alternatives from the prescription domain.</p>
 */
public final class SubstitutionContract {

    private SubstitutionContract() {
    }

    /**
     * Lifecycle of a substitution proposal.
     */
    public enum State {
        /** Proposed and awaiting the prescriber; nothing has changed. */
        PENDING("pending"),
        /** Approved by the prescriber; a signed revision now exists. */
        APPROVED("approved"),
        /** Declined by the prescriber; the original medication stands. */
        DECLINED("declined");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /**
     * Why the pharmacy proposes a substitution; a bounded, auditable reason.
     */
    public enum Reason {
        /** The originally prescribed item is out of stock. */
        STOCK("stock"),
        /** The pharmacy proposes a different brand of the same medicine. */
        BRAND("brand"),
        /** The pharmacy proposes a different formulation of the same medicine. */
        FORMULATION("formulation");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase reason key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored reason key.
         *
         * @param value stored wire key
         * @return the matching reason
         * @throws ApiException when the key is unknown
         */
        public static Reason from(String value) {
            for (Reason candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new ApiException(422, "INVALID_SUBSTITUTION_REASON", "unknown substitution reason");
        }
    }

    /**
     * One pharmacy proposal, immutable in all states.
     *
     * @return this immutable proposal
     */
    public record Proposal(String id, String organizationId, String prescriptionId, String patientId,
                           String prescriberSubject, String proposedBy, DrugEntry original,
                           DrugEntry proposed, Reason reason, String note, String overrideReason,
                           AllergyCheck allergyCheck, State state, int version, Instant proposedAt) {

        /**
         * Validate the proposal identity and bound collaborators.
         *
         * @throws ApiException when identity, version or a required collaborator is missing
         */
        public Proposal {
            SubstitutionInputs.requireText(id, "INVALID_PROPOSAL", "proposal id is required");
            SubstitutionInputs.requirePositive(version, "INVALID_PROPOSAL", "proposal version must be positive");
            if (original == null) {
                throw new ApiException(422, "INVALID_PROPOSAL", "a proposal needs the original catalog entry");
            }
            if (proposed == null) {
                throw new ApiException(422, "INVALID_PROPOSAL", "a proposal needs the proposed catalog entry");
            }
            if (reason == null) {
                throw new ApiException(422, "INVALID_PROPOSAL", "a proposal needs a reason");
            }
            if (allergyCheck == null) {
                throw new ApiException(422, "INVALID_PROPOSAL", "a proposal needs its allergy check");
            }
            if (state == null) {
                throw new ApiException(422, "INVALID_PROPOSAL", "a proposal needs a state");
            }
        }
    }

    /**
     * Immutable revision created only when the prescriber approves a proposal.
     *
     * <p>Provenance is the exact proposal, the replaced and replacement catalog
     * entries, the approving subject, the fresh allergy check, the prescriber's
     * attestation and the catalog approval provenance of the replacement.</p>
     *
     * @return this immutable signed revision
     */
    public record SignedRevision(String id, String prescriptionId, String proposalId, int revisionNumber,
                                 DrugEntry replaced, DrugEntry replacement, Reason reason, String approvedBy,
                                 AllergyCheck allergyCheck, String attestation, String catalogProvenance,
                                 Instant signedAt) {

        /**
         * Validate the revision identity, provenance and bound entries.
         *
         * @throws ApiException when identity, provenance, version or an entry is missing
         */
        public SignedRevision {
            SubstitutionInputs.requireText(id, "INVALID_SUBSTITUTION_REVISION", "revision id is required");
            SubstitutionInputs.requireText(attestation, "INVALID_SUBSTITUTION_REVISION",
                    "a signed revision needs the prescriber attestation");
            SubstitutionInputs.requireText(catalogProvenance, "INVALID_SUBSTITUTION_REVISION",
                    "revision catalog provenance is required");
            SubstitutionInputs.requirePositive(revisionNumber, "INVALID_SUBSTITUTION_REVISION",
                    "revision number must be positive");
            if (replaced == null) {
                throw new ApiException(422, "INVALID_SUBSTITUTION_REVISION", "a revision needs the replaced entry");
            }
            if (replacement == null) {
                throw new ApiException(422, "INVALID_SUBSTITUTION_REVISION", "a revision needs the replacement entry");
            }
            if (allergyCheck == null) {
                throw new ApiException(422, "INVALID_SUBSTITUTION_REVISION", "a revision needs its allergy check");
            }
        }
    }

    /**
     * Read model placing the original and proposed entries side by side.
     *
     * @return this immutable view
     */
    public record ProposalView(Proposal proposal, SignedRevision appliedRevision, List<Alternative> alternatives) {

        /**
         * Validate the view and freeze the alternatives against caller mutation.
         *
         * @throws ApiException when the proposal is missing
         */
        public ProposalView {
            if (proposal == null) {
                throw new ApiException(422, "INVALID_PROPOSAL_VIEW", "a proposal view needs a proposal");
            }
            alternatives = List.copyOf(alternatives);
        }

        /**
         * Return an independent snapshot of the tagged alternatives.
         *
         * @return the alternatives in catalog order
         */
        @Override
        public List<Alternative> alternatives() {
            return List.copyOf(alternatives);
        }
    }
}
