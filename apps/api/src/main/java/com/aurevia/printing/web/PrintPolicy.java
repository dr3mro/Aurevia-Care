package com.aurevia.printing.web;

import java.util.Set;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.printing.HandoutKind;

/**
 * Default-deny print decision for one freshly derived caller and one record (IAM-01, ARC-02).
 *
 * <p>The named role-action is re-derived from active membership on every call,
 * never accepted from the request. The facility check uses the facility of the
 * record, not a caller-supplied facility selector. A record owned by another
 * tenant is concealed as not found so an existence probe reveals nothing.</p>
 */
public final class PrintPolicy {

    private PrintPolicy() {
    }

    /**
     * Decide whether any active role grants a print action for one handout kind.
     *
     * <p>The treating clinician may print every kind. Lab staff may print a lab
     * requisition, radiology staff an imaging requisition, and every other role
     * — including administrators and auditors — is denied.</p>
     *
     * @param kind  handout kind selected from the authoritative source
     * @param roles currently active role keys, never client-supplied
     * @return {@code true} only for an explicit grant
     */
    public static boolean allows(HandoutKind kind, Set<String> roles) {
        if (roles.contains("clinician")) {
            return true;
        }
        if (kind == HandoutKind.LAB_ORDER) {
            return roles.contains("lab_collector") || roles.contains("lab_verifier");
        }
        if (kind == HandoutKind.IMAGING_ORDER) {
            return roles.contains("radiology_staff") || roles.contains("radiology_interpreter");
        }
        return false;
    }

    /**
     * Require an authenticated caller, matching tenant, in-scope record facility and print role.
     *
     * @param actor           freshly derived caller identity, possibly {@code null}
     * @param source          authoritative source revision selected for the handout
     * @param facilityInScope whether the record facility is inside the caller's scope
     * @throws ApiException with 401 when the actor is absent, 404 for a foreign
     *                      tenant, or 403 for a facility or role denial
     */
    public static void authorize(Actor actor, HandoutSource source, boolean facilityInScope) {
        if (actor == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        if (!source.organizationId().equals(actor.organizationId())) {
            throw new ApiException(404, "NOT_FOUND", "handout source not found");
        }
        if (!facilityInScope) {
            throw new ApiException(403, "FACILITY_OUT_OF_SCOPE",
                    "the record facility is outside the caller's print scope");
        }
        if (!allows(source.kind(), actor.roles())) {
            throw new ApiException(403, "FORBIDDEN", "missing print permission");
        }
    }
}
