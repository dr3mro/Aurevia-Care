package com.aurevia.orderentry.catalog;

import java.time.Instant;
import java.util.Arrays;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.order.OrderInputs;
import com.aurevia.radiology.RadTypes.Modality;

/**
 * One immutable, versioned, effective-dated orderable laboratory test or imaging study (LAB-01, RAD-01, XCH-01).
 *
 * <p>An item is tenant- and facility-owned and addressed by a stable local
 * {@code itemId} plus an append-only {@code version}. A new definition appends a
 * new version with a later effective window; an old version is never rewritten.
 * An order captures the item id and version, so the exact snapshot it used stays
 * available even after a newer version takes effect or the older one is
 * retired. The single {@code specimenOrModality} attribute carries a specimen
 * type for a laboratory test and an approved modality wire code for an imaging
 * study; the {@link com.aurevia.orderentry.catalog.OrderableKind}
 * discriminates the two. Default priority reuses the order-exchange priority
 * rule instead of defining a second vocabulary.</p>
 *
 * @return the validated immutable orderable item
 */
public record OrderableItem(String organizationId, String facilityId, String itemId, int version,
                            String code, String name, OrderableKind kind, String specimenOrModality,
                            String preparationInstructions, String defaultPriority, String partnerId,
                            Instant effectiveFrom, Instant effectiveTo, OrderableStatus status) {

    /**
     * Validate the item at the catalog trust boundary and normalize its default priority.
     *
     * @throws ApiException when an identity field is missing, a version or enum is invalid,
     *                      the effective window is closed backwards, the priority is not approved,
     *                      or an imaging study names an unapproved modality
     */
    public OrderableItem {
        requireText(organizationId, "organizationId");
        requireText(facilityId, "facilityId");
        requireText(itemId, "itemId");
        requireText(code, "code");
        requireText(name, "name");
        requireText(specimenOrModality, "specimenOrModality");
        requireText(partnerId, "partnerId");
        if (version < 1) {
            throw new ApiException(422, "INVALID_ORDERABLE_ITEM", "version must be positive");
        }
        if (kind == null) {
            throw new ApiException(422, "INVALID_ORDERABLE_ITEM", "kind is required");
        }
        if (status == null) {
            throw new ApiException(422, "INVALID_ORDERABLE_ITEM", "status is required");
        }
        if (effectiveFrom == null) {
            throw new ApiException(422, "INVALID_ORDERABLE_ITEM", "effectiveFrom is required");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new ApiException(422, "INVALID_ORDERABLE_ITEM", "effectiveTo must follow effectiveFrom");
        }
        defaultPriority = OrderInputs.priority(defaultPriority);
        if (kind == OrderableKind.IMAGING_STUDY && !isApprovedModality(specimenOrModality)) {
            throw new ApiException(422, "INVALID_ORDERABLE_ITEM", "unapproved imaging modality");
        }
    }

    /**
     * Whether this version is in force at the supplied instant, ignoring its retired state.
     *
     * @param at evaluation instant
     * @return {@code true} from the effective start (inclusive) up to the optional end (exclusive)
     */
    public boolean effectiveAt(Instant at) {
        return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }

    /**
     * Whether this version may be selected for a new order at the supplied instant.
     *
     * @param at evaluation instant
     * @return {@code true} only for an active version inside its effective window
     */
    public boolean orderableAt(Instant at) {
        return status == OrderableStatus.ACTIVE && effectiveAt(at);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_ORDERABLE_ITEM", field + " is required");
        }
    }

    private static boolean isApprovedModality(String value) {
        return Arrays.stream(Modality.values()).anyMatch(modality -> modality.wire().equals(value));
    }
}
