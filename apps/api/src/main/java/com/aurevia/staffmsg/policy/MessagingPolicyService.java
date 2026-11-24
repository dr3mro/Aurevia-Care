package com.aurevia.staffmsg.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.staffmsg.policy.MessagingPolicyContract.MessageRule;
import com.aurevia.staffmsg.policy.MessagingPolicyContract.MessagingPolicySettings;
import com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyRevision;
import com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyScope;

/**
 * Effective-dated, append-only messaging policy kernel (MSG-07).
 *
 * <p>The service owns the effective-date selection and the revision append
 * guards. It has no transport, Spring or persistence concerns: tenant identity
 * reaches it as arguments from the caller and durable state through the
 * injected history port. Reading never requires an administrator so other
 * staff messaging services can stamp a message with the settings that were
 * effective when it was created.</p>
 */
public final class MessagingPolicyService {

    /**
     * Settings served when no approved revision has opened yet. Cross-facility
     * messaging is closed, the documented three attachment types are allowed,
     * retention is the bounded default, and the audience rule matches the
     * current conservative baseline. This is a fail-closed substitute for a
     * missing configuration, not a claim to be narrower than every possible
     * approved policy; the audience default is confirmed by the consumer
     * migration task.
     */
    private static final MessagingPolicySettings FAIL_CLOSED_DEFAULT = new MessagingPolicySettings(
            MessageRule.ALL_STAFF, EnumSet.allOf(MediaType.class),
            MessagingPolicySettings.DEFAULT_RETENTION_DAYS, false, Instant.EPOCH, 0L);

    /** Newest opened revision wins; version breaks a tie on the effective instant. */
    private static final Comparator<PolicyRevision> EFFECTIVE_ORDER =
            Comparator.comparing((PolicyRevision revision) -> revision.settings().effectiveFrom())
                    .thenComparingLong(revision -> revision.settings().version());

    /** History is ordered by append instant, then version for same-instant edits. */
    private static final Comparator<PolicyRevision> HISTORY_ORDER =
            Comparator.comparing(PolicyRevision::recordedAt)
                    .thenComparingLong(revision -> revision.settings().version());

    private final MessagingPolicyHistory history;

    /**
     * Create the kernel over its revision history port.
     *
     * @param history tenant-scoped append-only revision history
     */
    public MessagingPolicyService(MessagingPolicyHistory history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    /**
     * Resolve the policy settings effective at an instant.
     *
     * <p>A revision whose effective instant is in the future is not selected,
     * so publishing a future change cannot alter messages created before it.
     * Ties on the effective instant are broken by the higher version.</p>
     *
     * @param organizationId owning organization
     * @param scope          tenant or facility scope
     * @param facilityCode   facility code for facility scope, otherwise {@code null}
     * @param at             instant at which the policy is needed
     * @return the effective settings, or the fail-closed default when none has opened
     */
    public MessagingPolicySettings policyAt(String organizationId, PolicyScope scope,
                                            String facilityCode, Instant at) {
        return history.revisions(organizationId, scope, facilityCode).stream()
                .filter(revision -> !revision.settings().effectiveFrom().isAfter(at))
                .max(EFFECTIVE_ORDER)
                .map(PolicyRevision::settings)
                .orElse(FAIL_CLOSED_DEFAULT);
    }

    /**
     * Read the immutable revision history in append order.
     *
     * @param organizationId owning organization
     * @param scope          tenant or facility scope
     * @param facilityCode   facility code for facility scope, otherwise {@code null}
     * @return an immutable, ordered snapshot of every retained revision
     */
    public List<PolicyRevision> history(String organizationId, PolicyScope scope, String facilityCode) {
        List<PolicyRevision> ordered = new ArrayList<>(history.revisions(organizationId, scope, facilityCode));
        ordered.sort(HISTORY_ORDER);
        return List.copyOf(ordered);
    }

    /**
     * Append a new policy revision.
     *
     * <p>The version must be unique inside the scope and the effective instant
     * must not precede the creation instant, so a caller cannot silently
     * rewrite an earlier policy or back-date a change to affect past messages.
     * A facility code is required exactly for facility scope; otherwise a
     * revision would be unreachable by any matching port lookup. This method
     * is package-private on purpose: writes must pass the admin gate in
     * {@code MessagingPolicyActions}.</p>
     *
     * @param organizationId owning organization
     * @param scope          tenant or facility scope
     * @param facilityCode   facility code for facility scope, otherwise {@code null}
     * @param settings       validated settings for the new revision
     * @param changedBy      authenticated actor appending the revision
     * @param now            server creation instant
     * @return the appended immutable revision
     * @throws ApiException with status 422 when the facility code does not match
     *         the scope or the effective instant is back-dated, or status 409
     *         when the version already exists in this scope
     */
    PolicyRevision edit(String organizationId, PolicyScope scope, String facilityCode,
                        MessagingPolicySettings settings, String changedBy, Instant now) {
        if ((scope == PolicyScope.FACILITY) != (facilityCode != null)) {
            throw new ApiException(422, "INVALID_POLICY_SCOPE",
                    "a facility code is required exactly for facility scope");
        }
        boolean duplicate = history.revisions(organizationId, scope, facilityCode).stream()
                .anyMatch(revision -> revision.settings().version() == settings.version());
        if (duplicate) {
            throw new ApiException(409, "DUPLICATE_POLICY_VERSION",
                    "this policy version already exists in the scope");
        }
        if (settings.effectiveFrom().isBefore(now)) {
            throw new ApiException(422, "BACKDATED_POLICY_EFFECTIVE_FROM",
                    "a policy revision cannot be effective before it is created");
        }
        PolicyRevision revision = new PolicyRevision(UUID.randomUUID().toString(), organizationId, scope,
                facilityCode, settings, changedBy, now);
        history.append(revision);
        return revision;
    }
}
