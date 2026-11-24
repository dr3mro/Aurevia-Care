package com.aurevia.orderentry.delivery;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.aurevia.api.ApiException;

/**
 * Registry of partner interfaces this organization has approved for outbound order delivery (PRD-02).
 *
 * <p>A partner with no entry has no approved interface: the kernel refuses an
 * electronic send and the order can only be recorded through the evidence-bearing
 * manual or print fallback. The registry is a defensive copy of its constructor
 * input so a caller cannot change approvals after construction.</p>
 */
public final class ApprovedChannelRegistry {

    private final Map<String, PartnerChannel> channels;

    /**
     * Create a registry from approved partner channels.
     *
     * @param approved partner code to approved channel, copied defensively
     */
    public ApprovedChannelRegistry(Map<String, PartnerChannel> approved) {
        this.channels = new HashMap<>(Objects.requireNonNull(approved, "approved"));
    }

    /**
     * Register an approved channel for a partner code.
     *
     * @param partnerCode partner interface key
     * @param channel     approved outbound channel
     */
    public void register(String partnerCode, PartnerChannel channel) {
        channels.put(partnerCode, Objects.requireNonNull(channel, "channel"));
    }

    /**
     * Whether the partner has an approved electronic interface.
     *
     * @param partnerCode partner interface key, possibly {@code null}
     * @return {@code true} only when an approved channel is registered
     */
    public boolean isApproved(String partnerCode) {
        return partnerCode != null && channels.containsKey(partnerCode);
    }

    /**
     * Resolve the approved channel for a partner code.
     *
     * @param partnerCode partner interface key
     * @return the approved channel
     * @throws ApiException {@code 422} when the partner has no approved interface
     */
    public PartnerChannel channel(String partnerCode) {
        if (!isApproved(partnerCode)) {
            throw new ApiException(422, "NO_APPROVED_INTERFACE", "the partner has no approved interface");
        }
        return channels.get(partnerCode);
    }
}
