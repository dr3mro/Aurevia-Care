package com.aurevia.orderentry.catalog;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.aurevia.api.ApiException;

/**
 * Pure fold over one tenant- and facility-scoped set of versioned orderable items (LAB-01, RAD-01, XCH-01).
 *
 * <p>The caller supplies only already tenant-scoped reference data and the
 * organization, facility and evaluation instant for each query; this type does
 * not read {@link com.aurevia.tenant.TenantContext} and is not an
 * isolation boundary on its own. All three query methods filter the same tenant
 * and facility keys, so two organizations with identical local item codes see
 * only their own entries. Effective dates are UTC instants: presenting them in a
 * facility's local time zone is the caller's presentation concern (UX-01).</p>
 *
 * <p>Search and resolution expose only active versions inside their effective
 * window, so a retired or not-yet-effective item cannot be selected for a new
 * order. The separate snapshot lookup deliberately ignores status and window:
 * it returns the exact immutable version an earlier order captured. Effective
 * windows are half-open (start inclusive, end exclusive), matching the drug
 * and radiology catalog folds. Every item version is unique within its tenant,
 * facility and local key so resolution is independent of input order.</p>
 */
public final class OrderableCatalog {

    private static final Comparator<OrderableItem> SEARCH_ORDER = Comparator.comparing(OrderableItem::code)
            .thenComparing(Comparator.comparingInt(OrderableItem::version).reversed());
    private static final Comparator<OrderableItem> VERSION_ORDER = Comparator.comparingInt(OrderableItem::version)
            .thenComparing(OrderableItem::effectiveFrom)
            .thenComparing(OrderableItem::itemId);

    private final List<OrderableItem> items;

    /**
     * Create a catalog from all stored versions, in any order.
     *
     * @param items all orderable items visible to the caller
     * @throws ApiException when the list is missing or a tenant-local identity/version is duplicated
     */
    public OrderableCatalog(List<OrderableItem> items) {
        if (items == null) {
            throw new ApiException(422, "INVALID_ORDERABLE_CATALOG", "catalog items are required");
        }
        this.items = List.copyOf(items);
        Set<String> seen = new HashSet<>();
        for (OrderableItem item : this.items) {
            String identity = key(item.organizationId(), item.facilityId(), item.itemId(), item.version());
            String codeVersion = key(item.organizationId(), item.facilityId(), item.code(), item.version());
            if (!seen.add(identity) || !seen.add(codeVersion)) {
                throw new ApiException(422, "INVALID_ORDERABLE_CATALOG", "duplicate orderable version");
            }
        }
    }

    /**
     * Search orderable items by a case-insensitive name or code fragment at an instant.
     *
     * @param organizationId active tenant
     * @param facilityId     performing facility
     * @param query          fragment, or {@code null}/blank for every orderable item
     * @param at             evaluation instant
     * @return visible, orderable matches ordered by code then newest version
     * @throws ApiException when the scope or evaluation instant is invalid
     */
    public List<OrderableItem> search(String organizationId, String facilityId, String query, Instant at) {
        requireText(organizationId, "organizationId");
        requireText(facilityId, "facilityId");
        requireInstant(at);
        return items.stream()
                .filter(item -> scoped(item, organizationId, facilityId) && item.orderableAt(at))
                .filter(item -> matches(item, query))
                .sorted(SEARCH_ORDER)
                .toList();
    }

    /**
     * Resolve the newest orderable version of one exact code at an instant.
     *
     * @param organizationId active tenant
     * @param facilityId     performing facility
     * @param code           exact tenant-local order code
     * @param at             evaluation instant
     * @return the newest orderable version, or empty when none is orderable
     * @throws ApiException when the scope or evaluation instant is invalid
     */
    public Optional<OrderableItem> resolve(String organizationId, String facilityId, String code, Instant at) {
        requireText(organizationId, "organizationId");
        requireText(facilityId, "facilityId");
        requireText(code, "code");
        requireInstant(at);
        return items.stream()
                .filter(item -> scoped(item, organizationId, facilityId) && item.code().equals(code))
                .filter(item -> item.orderableAt(at))
                .max(VERSION_ORDER);
    }

    /**
     * Return the exact immutable version a previously placed order captured.
     *
     * <p>Unlike search and resolution, a snapshot ignores retirement and
     * effective windows so an old order keeps showing the catalog entry it used.</p>
     *
     * @param organizationId active tenant
     * @param facilityId     performing facility
     * @param itemId         stable local item identifier
     * @param version        catalog version captured by the order
     * @return the exact historical item, or empty when it is not visible
     * @throws ApiException when the scope or version is invalid
     */
    public Optional<OrderableItem> snapshot(String organizationId, String facilityId, String itemId, int version) {
        requireText(organizationId, "organizationId");
        requireText(facilityId, "facilityId");
        requireText(itemId, "itemId");
        if (version < 1) {
            throw new ApiException(422, "INVALID_ORDERABLE_SCOPE", "version must be positive");
        }
        return items.stream()
                .filter(item -> scoped(item, organizationId, facilityId))
                .filter(item -> item.itemId().equals(itemId) && item.version() == version)
                .findFirst();
    }

    private static boolean scoped(OrderableItem item, String organizationId, String facilityId) {
        return item.organizationId().equals(organizationId) && item.facilityId().equals(facilityId);
    }

    private static boolean matches(OrderableItem item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return item.name().toLowerCase(Locale.ROOT).contains(needle)
                || item.code().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String key(String organizationId, String facilityId, String localId, int version) {
        return organizationId + '\u001f' + facilityId + '\u001f' + localId + '\u001f' + version;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_ORDERABLE_SCOPE", field + " is required");
        }
    }

    private static void requireInstant(Instant value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_ORDERABLE_SCOPE", "an evaluation instant is required");
        }
    }
}
