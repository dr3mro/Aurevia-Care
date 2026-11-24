package com.aurevia.documents;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentActions.Action;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.EventType;

/**
 * Shared authorization and append-only audit for both document services.
 *
 * <p>A denial is recorded before it is thrown, and no recorded fact carries a
 * payload, original file name or URL. Actions come from one policy table so
 * the two services cannot drift apart. A missing actor fails closed without an
 * audit record because there is no tenant to attribute it to.</p>
 */
@Component
public final class DocumentGuard {

    private final DocumentStore store;
    private final Clock clock;

    /**
     * Create the guard over the shared tenant-scoped store.
     *
     * @param store tenant-scoped persistence boundary
     * @param clock source of server timestamps
     */
    public DocumentGuard(DocumentStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Require an active role that permits the action.
     *
     * @param actor      freshly derived caller identity, possibly {@code null}
     * @param action     protected action
     * @param resourceId selector to attribute in the audit fact
     * @throws ApiException when the actor is absent or lacks the action
     */
    public void authorize(Actor actor, Action action, String resourceId) {
        if (actor == null) {
            throw new ApiException(403, "FORBIDDEN", "missing " + action + " permission");
        }
        boolean permitted = actor.roles().stream()
                .anyMatch(role -> DocumentActions.allows(action, role));
        if (!permitted) {
            throw deny(actor, resourceId, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    /**
     * Build and record a forbidden failure.
     *
     * @param actor      caller identity
     * @param resourceId selector to attribute
     * @param code       stable machine-readable code
     * @param message    non-sensitive detail
     * @return the failure to throw
     */
    public ApiException deny(Actor actor, String resourceId, String code, String message) {
        record(actor, resourceId, EventType.ACCESS_DENIED, "denied", code);
        return new ApiException(403, code, message);
    }

    /**
     * Build and record a conflicting-state failure.
     *
     * @param actor      caller identity
     * @param resourceId selector to attribute
     * @param code       stable machine-readable code
     * @param message    non-sensitive detail
     * @return the failure to throw
     */
    public ApiException conflict(Actor actor, String resourceId, String code, String message) {
        record(actor, resourceId, EventType.ACCESS_DENIED, "conflict", code);
        return new ApiException(409, code, message);
    }

    /**
     * Build and record a stale-version precondition failure.
     *
     * @param actor      caller identity
     * @param resourceId selector to attribute
     * @param code       stable machine-readable code
     * @param message    non-sensitive detail
     * @return the failure to throw
     */
    public ApiException stale(Actor actor, String resourceId, String code, String message) {
        record(actor, resourceId, EventType.ACCESS_DENIED, "conflict", code);
        return new ApiException(412, code, message);
    }

    /**
     * Build and record an invalid-input failure.
     *
     * @param actor      caller identity
     * @param resourceId selector to attribute
     * @param code       stable machine-readable code
     * @param message    non-sensitive detail
     * @return the failure to throw
     */
    public ApiException invalid(Actor actor, String resourceId, String code, String message) {
        record(actor, resourceId, EventType.ACCESS_DENIED, "invalid", code);
        return new ApiException(422, code, message);
    }

    /**
     * Build a concealment failure. No fact is recorded so an existence probe
     * cannot be distinguished from a missing row by reading the ledger.
     *
     * @return the failure to throw
     */
    public ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "document artifact not found");
    }

    /**
     * Append one immutable fact.
     *
     * @param actor      caller identity
     * @param resourceId owning artifact, job or revision identifier
     * @param type       fact type
     * @param outcome    {@code success}, {@code denied}, {@code conflict} or {@code invalid}
     * @param detail     bounded non-sensitive detail, possibly {@code null}
     */
    public void record(Actor actor, String resourceId, EventType type, String outcome, String detail) {
        store.appendEvent(new DocumentEvent(ArtifactInputs.newId(), actor.organizationId(),
                resourceId, type, actor.subject(), outcome, detail, clock.instant()));
    }
}
