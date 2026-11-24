package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * WEB-06 tests for the source-address gate on the metrics endpoint.
 *
 * <p>All addresses are synthetic documentation ranges or loopback. The default
 * policy must expose metrics only to loopback; an explicit CIDR list extends the
 * allowlist without removing loopback, and malformed configuration fails closed.</p>
 */
class MetricsAccessPolicyTest {

    @Test
    void defaultsToLoopbackOnly() {
        MetricsAccessPolicy policy = new MetricsAccessPolicy("");

        assertTrue(policy.allows("127.0.0.1"));
        assertTrue(policy.allows("::1"));
        assertFalse(policy.allows("198.51.100.10"));
    }

    @Test
    void configuredCidrExtendsLoopbackAndIgnoresBlankEntries() {
        MetricsAccessPolicy policy = new MetricsAccessPolicy(" , 198.51.100.0/24 ,  ");

        assertTrue(policy.allows("127.0.0.1"));
        assertTrue(policy.allows("198.51.100.10"));
        assertFalse(policy.allows("203.0.113.5"));
    }

    @Test
    void invalidCidrFailsClosedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsAccessPolicy("10.0.0.0/33"));
    }
}
