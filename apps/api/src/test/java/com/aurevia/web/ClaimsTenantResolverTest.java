package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/** Unit tests for the pure claims-to-tenant resolver; no Spring, no database. */
class ClaimsTenantResolverTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ORG_OTHER = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "user-123";
    private static final String ISSUER = "https://issuer.example.test";
    private static final String CORRELATION = "corr-1";

    private final ClaimsTenantResolver resolver = new ClaimsTenantResolver();

    private static Map<String, Object> claims(String subject, Object orgs, Object issuer) {
        Map<String, Object> claims = new HashMap<>();
        if (subject != null) {
            claims.put("sub", subject);
        }
        if (orgs != null) {
            claims.put("orgs", orgs);
        }
        if (issuer != null) {
            claims.put("iss", issuer);
        }
        return claims;
    }

    private void assertUnauthenticated(Map<String, Object> claims) {
        ApiException failure = assertThrows(ApiException.class,
                () -> resolver.resolve(claims, ORG_A, CORRELATION));
        assertEquals(401, failure.status());
        assertEquals("UNAUTHENTICATED", failure.code());
        assertEquals("authentication required", failure.getMessage());
    }

    private void assertInvalidSelection(String requestedOrganization) {
        ApiException failure = assertThrows(ApiException.class,
                () -> resolver.resolve(claims(SUBJECT, List.of(ORG_A), ISSUER),
                        requestedOrganization, CORRELATION));
        assertEquals(422, failure.status());
        assertEquals("INVALID_TENANT_SELECTION", failure.code());
        assertEquals("an active organization must be selected", failure.getMessage());
    }

    @Test
    void resolvesTenantForPermittedOrganization() {
        TenantContext context = resolver.resolve(
                claims(SUBJECT, List.of(ORG_A, ORG_B), ISSUER), ORG_B, CORRELATION);
        assertEquals(ORG_B, context.organizationId());
        assertEquals(SUBJECT, context.subject());
        assertEquals(TenantContext.PURPOSE, context.purpose());
        assertEquals(CORRELATION, context.correlationId());
    }

    @Test
    void rejectsClaimsWithoutSubject() {
        assertUnauthenticated(claims(null, List.of(ORG_A), ISSUER));
    }

    @Test
    void rejectsBlankSubject() {
        assertUnauthenticated(claims("   ", List.of(ORG_A), ISSUER));
    }

    @Test
    void rejectsClaimsWithoutOrganizations() {
        assertUnauthenticated(claims(SUBJECT, null, ISSUER));
    }

    @Test
    void rejectsOrganizationsClaimThatIsNotAList() {
        assertUnauthenticated(claims(SUBJECT, ORG_A, ISSUER));
    }

    @Test
    void rejectsEmptyOrganizationsClaim() {
        assertUnauthenticated(claims(SUBJECT, List.of(), ISSUER));
    }

    @Test
    void rejectsOrganizationsClaimContainingNonString() {
        assertUnauthenticated(claims(SUBJECT, List.of(ORG_A, 42), ISSUER));
    }

    @Test
    void rejectsOrganizationsClaimContainingMalformedUuid() {
        assertUnauthenticated(claims(SUBJECT, List.of("not-a-uuid"), ISSUER));
    }

    @Test
    void rejectsOrganizationNotListedInClaims() {
        ApiException failure = assertThrows(ApiException.class,
                () -> resolver.resolve(claims(SUBJECT, List.of(ORG_A), null),
                        ORG_OTHER, CORRELATION));
        assertEquals(403, failure.status());
        assertEquals("ORGANIZATION_NOT_PERMITTED", failure.code());
        assertEquals("organization is not available to this subject", failure.getMessage());
    }

    @Test
    void rejectsMalformedActiveOrganizationHeader() {
        assertInvalidSelection("not-a-uuid");
    }

    @Test
    void rejectsMissingActiveOrganizationHeader() {
        assertInvalidSelection(null);
    }

    @Test
    void principalFreezesOrganizationsAndSupportsValueSemantics() {
        Set<String> mutable = new LinkedHashSet<>(List.of(ORG_A));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(SUBJECT, mutable, ISSUER);
        mutable.clear();
        assertEquals(Set.of(ORG_A), principal.organizationIds());
        assertEquals(SUBJECT, principal.subject());
        assertEquals(ISSUER, principal.issuer());
        assertThrows(UnsupportedOperationException.class, () -> principal.organizationIds().clear());

        AuthenticatedPrincipal same = new AuthenticatedPrincipal(SUBJECT, Set.of(ORG_A), ISSUER);
        assertEquals(same, principal);
        assertEquals(same.hashCode(), principal.hashCode());
        assertNotEquals(principal, new AuthenticatedPrincipal("other", Set.of(ORG_A), ISSUER));
        assertTrue(principal.toString().contains("AuthenticatedPrincipal"));
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedPrincipal(SUBJECT, null, ISSUER));
    }
}
