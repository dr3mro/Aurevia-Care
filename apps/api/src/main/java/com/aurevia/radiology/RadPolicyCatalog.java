package com.aurevia.radiology;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadTypes.Modality;

/**
 * Approved, effective-dated radiology configuration for one tenant (ADM-01, RAD-01).
 *
 * <p>A modality may be scheduled only when the tenant has approved it. An
 * interpreter may sign only while holding an approved, effective privilege for
 * that specific modality. A future-dated or unapproved privilege never applies
 * early and there is no implicit blanket permission: an empty catalog denies
 * every interpretation. The caller supplies the evaluation instant so decisions
 * are deterministic and testable.</p>
 */
public final class RadPolicyCatalog {

    private final Set<Modality> approvedModalities;
    private final List<InterpreterPrivilege> privileges;

    /**
     * Create a catalog from approved configuration entries.
     *
     * @throws ApiException when either collection is missing
     */
    public RadPolicyCatalog(List<Modality> approvedModalities, List<InterpreterPrivilege> privileges) {
        if (approvedModalities == null || privileges == null) {
            throw new ApiException(422, "INVALID_RAD_POLICY", "radiology policy collections are required");
        }
        this.approvedModalities = Set.copyOf(approvedModalities);
        this.privileges = List.copyOf(privileges);
    }

    /**
     * Whether the tenant has approved a modality for scheduling.
     *
     * @return {@code true} when the modality is on the approved list
     */
    public boolean modalityApproved(Modality modality) {
        return modality != null && approvedModalities.contains(modality);
    }

    /**
     * Whether a subject holds an approved, effective privilege to interpret a modality.
     *
     * @return {@code true} when at least one approved effective privilege applies
     */
    public boolean mayInterpret(String subject, Modality modality, Instant at) {
        return privileges.stream().anyMatch(privilege -> privilege.subject().equals(subject)
                && privilege.modality() == modality && privilege.effectiveAt(at));
    }

    /**
     * One approved, effective-dated imaging privilege (ADM-01, RAD-01).
     *
     * @param subject       clinician subject
     * @param modality      imaging modality
     * @param approved      whether the privilege is approved
     * @param effectiveFrom effective start instant
     * @param effectiveTo   effective end instant, or {@code null}
     * @return this record
     */
    public record InterpreterPrivilege(String subject, Modality modality, boolean approved,
                                       Instant effectiveFrom, Instant effectiveTo) {

        /**
         * Reject an incomplete privilege before it can authorize a signature.
         *
         * @throws ApiException when identity, modality or effective start is missing
         */
        public InterpreterPrivilege {
            if (subject == null || subject.isBlank() || modality == null || effectiveFrom == null) {
                throw new ApiException(422, "INVALID_INTERPRETER_PRIVILEGE",
                        "invalid interpreter imaging privilege");
            }
        }

        /**
         * Whether this privilege is approved and effective at the supplied instant.
         *
         * @param at evaluation instant
         * @return {@code true} when the privilege may be applied
         */
        public boolean effectiveAt(Instant at) {
            return approved && !at.isBefore(effectiveFrom)
                    && (effectiveTo == null || at.isBefore(effectiveTo));
        }
    }
}
