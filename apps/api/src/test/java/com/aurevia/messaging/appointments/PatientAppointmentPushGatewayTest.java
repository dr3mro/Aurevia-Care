package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Deterministic evidence for the production local-only push boundary.
 *
 * <p>The bean opens no connection and reports a dispatch attempt, never provider
 * receipt. This test asserts both the returned disposition and that the only
 * notice it can carry is the fixed clinical-detail-free template.</p>
 */
class PatientAppointmentPushGatewayTest {

    /** The inert bean accepts the fixed notice without exposing any client detail. */
    @Test
    void sendReportsDeliveryOfTheFixedGenericNotice() {
        GenericPushNotice notice = GenericPushNotice.appointmentUpdate();
        PushGateway gateway = new PatientAppointmentPushGateway();

        assertEquals(PushGateway.Outcome.DELIVERED, gateway.send("synthetic-provider-token", notice));
        assertFalse(notice.reveals("patient-1"));
        assertFalse(notice.reveals("synthetic-provider-token"));
    }
}
