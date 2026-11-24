package com.aurevia.orderentry.place;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.IdempotencyKey;

/**
 * Order-placement domain kernel: drafts, immutable placement and idempotent retry (LAB-01, RAD-01, XCH-01, XCH-02).
 *
 * <p>The kernel owns draft and placed-order state and the decisions that make a
 * placement safe: only a clinician with ordering privilege in the active
 * organization may act; every draft edit and placement carries the version the
 * caller last read; a placement is idempotent by tenant, authenticated subject
 * and key; and a placed order is immutable. The caller supplies the active
 * tenant, subject and freshly resolved roles on every command and must
 * re-authorize on every request. This slice deliberately has no HTTP,
 * persistence or outbox dependency: a later story persists the returned
 * revisions and the idempotency index inside one guarded transaction.</p>
 *
 * <p>The ordering privilege is the {@code clinician} role resolved from fresh
 * active membership. Facility scope is carried on the request and bound to the
 * catalog items in the integration story that persists this kernel, exactly as
 * the other domain kernels in the repository defer their storage boundary.</p>
 */
@Service
public final class OrderPlacementService {

    /** Stable command name used to scope idempotency keys. */
    public static final String PLACE_COMMAND = "orders.place";

    private static final String ORDERING_ROLE = "clinician";

    private final Clock clock;
    private final Map<String, OrderDraft> drafts = new ConcurrentHashMap<>();
    private final Map<String, PlacedOrder> orders = new ConcurrentHashMap<>();
    private final Map<IdempotencyScope, PlacedOrder> processed = new ConcurrentHashMap<>();

    /**
     * Create the kernel over an injected clock.
     *
     * @param clock application clock used for draft and placement timestamps
     */
    public OrderPlacementService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Save a new editable draft in the active tenant.
     *
     * @param organizationId active tenant, never inferred from the request body
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles of the subject
     * @param request        validated order content
     * @return the stored draft at version one
     * @throws ApiException on invalid scope, missing privilege or invalid content
     */
    public OrderDraft saveDraft(String organizationId, String subject, Set<String> roles,
                                PlacedOrderRequest request) {
        requireScope(organizationId, subject);
        requireOrderingPrivilege(roles);
        OrderDraft draft = new OrderDraft(organizationId, UUID.randomUUID().toString(), 1, request,
                subject, clock.instant());
        drafts.put(key(organizationId, draft.id()), draft);
        return draft;
    }

    /**
     * Replace a draft's content with a new revision under a version precondition.
     *
     * @param organizationId active tenant
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles of the subject
     * @param draftId        tenant-local draft identifier
     * @param expectedVersion version the caller last read
     * @param request        validated replacement content
     * @return the new draft revision
     * @throws ApiException on invalid scope, missing privilege, an invisible draft or a stale version
     */
    public OrderDraft editDraft(String organizationId, String subject, Set<String> roles, String draftId,
                                int expectedVersion, PlacedOrderRequest request) {
        requireScope(organizationId, subject);
        requireOrderingPrivilege(roles);
        OrderDraft current = draft(organizationId, draftId);
        requireVersion(current.version(), expectedVersion);
        OrderDraft revised = current.revised(request);
        drafts.put(key(organizationId, draftId), revised);
        return revised;
    }

    /**
     * Place a draft, making it immutable and idempotent for the supplied key.
     *
     * <p>A retried placement with the same tenant, subject and key returns the
     * original order and creates no second one. Reusing the key for a different
     * draft is a conflict. The version precondition applies only to the first
     * placement; a replay is decided before the consumed draft is loaded.</p>
     *
     * @param organizationId active tenant
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles of the subject
     * @param draftId        tenant-local draft identifier
     * @param expectedVersion version the caller last read
     * @param key            validated idempotency key
     * @return the immutable placed order
     * @throws ApiException on invalid scope, missing privilege, an invisible or stale draft,
     *                      or an idempotency-key conflict
     */
    public PlacedOrder place(String organizationId, String subject, Set<String> roles, String draftId,
                             int expectedVersion, IdempotencyKey key) {
        requireScope(organizationId, subject);
        requireOrderingPrivilege(roles);
        IdempotencyScope scope = new IdempotencyScope(organizationId, subject, PLACE_COMMAND, key.value());
        PlacedOrder existing = processed.get(scope);
        if (existing != null) {
            if (!existing.draftId().equals(draftId)) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "the key belongs to a different order");
            }
            return existing;
        }
        OrderDraft current = draft(organizationId, draftId);
        requireVersion(current.version(), expectedVersion);
        Instant now = clock.instant();
        PlacedOrder placed = new PlacedOrder(organizationId, UUID.randomUUID().toString(), draftId, 1,
                current.request(), current.request().canonicalPayload(), subject, now, key.value());
        orders.put(key(organizationId, placed.id()), placed);
        processed.put(scope, placed);
        drafts.remove(key(organizationId, draftId));
        return placed;
    }

    /**
     * Read one placed order in the active tenant.
     *
     * @param organizationId active tenant
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles of the subject
     * @param orderId        tenant-local placed-order identifier
     * @return the immutable placed order
     * @throws ApiException on invalid scope, missing privilege or an invisible order
     */
    public PlacedOrder get(String organizationId, String subject, Set<String> roles, String orderId) {
        requireScope(organizationId, subject);
        requireOrderingPrivilege(roles);
        PlacedOrder order = orders.get(key(organizationId, orderId));
        if (order == null) {
            throw new ApiException(404, "NOT_FOUND", "order not found");
        }
        return order;
    }

    private static void requireScope(String organizationId, String subject) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ApiException(422, "INVALID_ORGANIZATION", "an active organization is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication is required");
        }
    }

    private static void requireOrderingPrivilege(Set<String> roles) {
        if (roles == null || !roles.contains(ORDERING_ROLE)) {
            throw new ApiException(403, "FORBIDDEN", "a clinician ordering privilege is required");
        }
    }

    private OrderDraft draft(String organizationId, String draftId) {
        OrderDraft draft = drafts.get(key(organizationId, draftId));
        if (draft == null) {
            throw new ApiException(404, "NOT_FOUND", "order draft not found");
        }
        return draft;
    }

    private static void requireVersion(int currentVersion, int expectedVersion) {
        if (currentVersion != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the order draft changed since it was read");
        }
    }

    private static String key(String organizationId, String localId) {
        return organizationId + '\u001f' + localId;
    }

    private record IdempotencyScope(String organizationId, String subject, String command, String key) {
    }
}
