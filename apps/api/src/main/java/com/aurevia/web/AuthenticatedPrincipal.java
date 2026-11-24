package com.aurevia.web;

import java.util.Set;

/**
 * Authenticated identity derived from already-verified token claims.
 *
 * <p>The organization set is the complete list of tenants the subject may
 * select; it is frozen on construction so a caller cannot widen or narrow the
 * entitlement after validation. The issuer is retained for provenance but is
 * not itself an authorization decision.</p>
 *
 * @param subject         authenticated subject from the {@code sub} claim
 * @param organizationIds organizations entitled to this subject
 * @param issuer          token issuer, or an empty string when absent
 * @return this record
 */
public record AuthenticatedPrincipal(String subject, Set<String> organizationIds, String issuer) {

    /**
     * Copy the caller's organization set so the principal cannot be changed through it.
     *
     * @throws NullPointerException when organizationIds is null
     */
    public AuthenticatedPrincipal {
        organizationIds = Set.copyOf(organizationIds);
    }

    /**
     * Return an immutable copy of the selectable organizations.
     *
     * @return organizations entitled to this subject
     */
    @Override
    public Set<String> organizationIds() {
        return Set.copyOf(organizationIds);
    }
}
