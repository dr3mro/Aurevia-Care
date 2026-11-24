package com.aurevia.orderentry.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Partner approval gate: only registered interfaces may be used for electronic delivery (PRD-02). */
class ApprovedChannelRegistryTest {

    @Test
    void a_registered_partner_is_approved_and_resolvable() {
        PartnerChannel channel = request -> SendOutcome.DELIVERED;
        ApprovedChannelRegistry registry = new ApprovedChannelRegistry(Map.of("partner-lab", channel));

        assertTrue(registry.isApproved("partner-lab"));
        assertSame(channel, registry.channel("partner-lab"));
    }

    @Test
    void an_unregistered_or_null_partner_is_not_approved() {
        ApprovedChannelRegistry registry = new ApprovedChannelRegistry(Map.of());

        assertFalse(registry.isApproved("missing"));
        assertFalse(registry.isApproved(null));
    }

    @Test
    void channel_of_an_unapproved_partner_fails_closed() {
        ApprovedChannelRegistry registry = new ApprovedChannelRegistry(Map.of());

        ApiException error = assertThrows(ApiException.class, () -> registry.channel("missing"));
        assertEquals(422, error.status());
        assertEquals("NO_APPROVED_INTERFACE", error.code());
        assertThrows(ApiException.class, () -> registry.channel(null));
    }

    @Test
    void register_adds_an_approved_channel_to_the_registry() {
        ApprovedChannelRegistry registry = new ApprovedChannelRegistry(Map.of());
        assertFalse(registry.isApproved("partner-lab"));

        PartnerChannel channel = request -> SendOutcome.FAILED;
        registry.register("partner-lab", channel);

        assertTrue(registry.isApproved("partner-lab"));
        assertSame(channel, registry.channel("partner-lab"));
    }

    @Test
    void constructor_copies_the_supplied_approvals() {
        Map<String, PartnerChannel> source = new java.util.HashMap<>();
        source.put("partner-lab", request -> SendOutcome.DELIVERED);
        ApprovedChannelRegistry registry = new ApprovedChannelRegistry(source);

        source.clear();

        assertTrue(registry.isApproved("partner-lab"));
    }
}
