package com.aurevia.messaging.appointments;

/**
 * Port for the verified patient-account to chart link required before any
 * patient self-access.
 *
 * <p>The implementation must re-derive the link from the authenticated subject
 * and the active organization on every call. An absent or suspended link fails
 * closed; demographics and client-supplied patient identifiers are never a
 * substitute. This slice does not create links: the production adapter re-reads
 * the authoritative identity-proofing fold, while a synthetic fixture adapter
 * is only seeded for local HTTP tests.</p>
 */
public interface VerifiedPatientLink {

    /**
     * Decide whether the subject has a current verified link to this patient in
     * the active organization.
     *
     * @param subject   authenticated subject from the tenant context
     * @param patientId patient identifier being accessed
     * @return {@code true} only for an active, organization-scoped verified link
     */
    boolean allows(String subject, String patientId);
}
