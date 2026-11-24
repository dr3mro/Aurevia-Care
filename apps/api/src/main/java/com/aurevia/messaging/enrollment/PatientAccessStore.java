package com.aurevia.messaging.enrollment;

import java.util.List;
import java.util.Optional;

import com.aurevia.messaging.enrollment.EnrollmentContract.AccessEvent;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessToken;
import com.aurevia.messaging.enrollment.EnrollmentContract.GenericNotification;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportCase;

/**
 * Persistence boundary for privacy-safe patient access.
 *
 * <p>Every operation is tenant scoped: the organization identifier is part of
 * the key, so two tenants with identical local identifiers remain isolated
 * across invitations, accounts, links, cases, notifications and audit (ARC-02).
 * A follow-up story can bind the same domain service to a real PostgreSQL
 * repository without changing domain logic.</p>
 */
public interface PatientAccessStore {

    /**
     * Check that a synthetic patient chart exists in this tenant.
     *
     * @param organizationId active organization
     * @param chartId        tenant-local chart identifier
     * @return {@code true} when the chart is present in this tenant
     */
    boolean chartExists(String organizationId, String chartId);

    /**
     * Read one enrollment invitation by code.
     *
     * @param organizationId active organization
     * @param code           opaque invitation code
     * @return the invitation when present in this tenant
     */
    Optional<Invitation> invitation(String organizationId, String code);

    /**
     * Store or replace one invitation within the tenant.
     *
     * @param organizationId active organization
     * @param invitation     invitation to store
     */
    void putInvitation(String organizationId, Invitation invitation);

    /**
     * Atomically consume one pending, unexpired invitation. Exactly one caller
     * may win even when two redemption requests race, so an account is created
     * at most once per invitation (PAT-03, IAM-05).
     *
     * @param organizationId active organization
     * @param code           opaque invitation code
     * @return {@code true} only for the caller that consumed the invitation
     */
    boolean consumeInvitation(String organizationId, String code);

    /**
     * Persist a failed verification attempt on a pending invitation without
     * changing its state or re-storing the presented code (IAM-05).
     *
     * @param organizationId active organization
     * @param code           opaque invitation code
     * @param failedAttempts new consecutive failure count
     */
    void recordInvitationFailure(String organizationId, String code, int failedAttempts);

    /**
     * Read one account by identifier.
     *
     * @param organizationId active organization
     * @param accountId      tenant-local account identifier
     * @return the account when present in this tenant
     */
    Optional<PatientAccount> account(String organizationId, String accountId);

    /**
     * Read one account by its verified subject.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return the account when present in this tenant
     */
    Optional<PatientAccount> accountBySubject(String organizationId, String subject);

    /**
     * Store or replace one account within the tenant.
     *
     * @param organizationId active organization
     * @param account        account to store
     */
    void putAccount(String organizationId, PatientAccount account);

    /**
     * Read one short-lived access token.
     *
     * @param organizationId active organization
     * @param token          opaque token value
     * @return the token when present in this tenant
     */
    Optional<AccessToken> token(String organizationId, String token);

    /**
     * Store or replace one access token within the tenant.
     *
     * @param organizationId active organization
     * @param accessToken    token to store
     */
    void putToken(String organizationId, AccessToken accessToken);

    /**
     * Atomically mark a single-use token as used. The raw token is never
     * re-stored, so a successful verification cannot leave a reusable copy.
     *
     * @param organizationId active organization
     * @param token          opaque token value
     * @return {@code true} when an unused token was marked used
     */
    boolean consumeToken(String organizationId, String token);

    /**
     * Read one support case by identifier.
     *
     * @param organizationId active organization
     * @param caseId         tenant-local case identifier
     * @return the case when present in this tenant
     */
    Optional<SupportCase> supportCase(String organizationId, String caseId);

    /**
     * Store or replace one support case within the tenant.
     *
     * @param organizationId active organization
     * @param supportCase    case to store
     */
    void putSupportCase(String organizationId, SupportCase supportCase);

    /**
     * List support cases in this tenant in insertion order.
     *
     * @param organizationId active organization
     * @return support cases in this tenant
     */
    List<SupportCase> supportCases(String organizationId);

    /**
     * Read the tenant's append-only access audit trail.
     *
     * @param organizationId active organization
     * @return access facts in insertion order
     */
    List<AccessEvent> audit(String organizationId);

    /**
     * Append one access fact to the tenant's trail.
     *
     * @param event fact to append
     */
    void appendAudit(AccessEvent event);

    /**
     * Read the tenant's generic notifications in insertion order.
     *
     * @param organizationId active organization
     * @return generic notifications in this tenant
     */
    List<GenericNotification> notifications(String organizationId);

    /**
     * Append one generic notification. The type cannot carry clinical detail.
     *
     * @param notification notification to append
     */
    void appendNotification(GenericNotification notification);
}
