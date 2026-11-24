package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G3-01: only active device tokens may receive a push (PAT-01, ARC-02). */
class DeviceTokenTest {

    private static DeviceToken token(DeviceState state) {
        return new DeviceToken("dev-1", "synthetic-token", "patient-user-a", "patient-1", state);
    }

    @Test
    void onlyActiveTokensAreDeliverable() {
        assertTrue(DeviceState.ACTIVE.deliverable());
        assertFalse(DeviceState.REVOKED.deliverable());
        assertFalse(DeviceState.INVALID.deliverable());
    }

    @Test
    void revokeAndInvalidateReturnNewTokensWithoutMutatingTheOriginal() {
        DeviceToken active = token(DeviceState.ACTIVE);
        DeviceToken revoked = active.revoke();
        DeviceToken invalid = active.invalidate();
        assertNotSame(active, revoked);
        assertNotSame(active, invalid);
        assertEquals(DeviceState.REVOKED, revoked.state());
        assertEquals(DeviceState.INVALID, invalid.state());
        assertEquals(DeviceState.ACTIVE, active.state());
        assertEquals("dev-1", revoked.tokenId());
        assertEquals("patient-1", invalid.patientId());
        assertEquals("patient-user-a", revoked.subject());
        assertEquals("synthetic-token", invalid.token());
    }
}
