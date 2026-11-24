package com.aurevia.web;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/**
 * Derives a request tenant context from already-validated token claims.
 *
 * <p>This class does not parse, decode or verify a token. It receives a claims
 * map whose signature, audience and expiry a caller has already verified and
 * enforces the subject and organization-selection rules (IAM-01, ARC-02). The
 * requested organization is accepted only when it is a well-formed UUID that
 * appears in the token's {@code orgs} claim, so a client cannot choose a tenant
 * the token does not entitle it to.</p>
 */
public final class ClaimsTenantResolver {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Resolve the active tenant from verified claims and an explicit selection.
     *
     * @param claims                verified token claims
     * @param requestedOrganization value of the {@code X-Active-Organization} header
     * @param correlationId         request correlation identifier
     * @return the tenant context for this request
     * @throws ApiException when the subject, organization claim or selection is invalid
     */
    public TenantContext resolve(Map<String, Object> claims, String requestedOrganization, String correlationId) {
        AuthenticatedPrincipal principal = principal(claims);
        if (requestedOrganization == null || !UUID_PATTERN.matcher(requestedOrganization).matches()) {
            throw new ApiException(422, "INVALID_TENANT_SELECTION", "an active organization must be selected");
        }
        if (!principal.organizationIds().contains(requestedOrganization)) {
            throw new ApiException(403, "ORGANIZATION_NOT_PERMITTED",
                    "organization is not available to this subject");
        }
        return new TenantContext(requestedOrganization, principal.subject(), TenantContext.PURPOSE, correlationId);
    }

    private AuthenticatedPrincipal principal(Map<String, Object> claims) {
        Object subjectClaim = claims.get("sub");
        if (!(subjectClaim instanceof String subject) || subject.isBlank()) {
            throw unauthenticated();
        }
        Object organizationsClaim = claims.get("orgs");
        if (!(organizationsClaim instanceof List<?> organizations) || organizations.isEmpty()) {
            throw unauthenticated();
        }
        Set<String> organizationIds = new HashSet<>();
        for (Object organization : organizations) {
            if (!(organization instanceof String value) || !UUID_PATTERN.matcher(value).matches()) {
                throw unauthenticated();
            }
            organizationIds.add(value);
        }
        return new AuthenticatedPrincipal(subject, organizationIds, issuer(claims));
    }

    private String issuer(Map<String, Object> claims) {
        Object issuerClaim = claims.get("iss");
        return issuerClaim instanceof String value ? value : "";
    }

    private ApiException unauthenticated() {
        return new ApiException(401, "UNAUTHENTICATED", "authentication required");
    }
}
