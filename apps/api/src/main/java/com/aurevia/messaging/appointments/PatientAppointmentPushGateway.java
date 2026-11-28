package com.aurevia.messaging.appointments;

import org.springframework.stereotype.Component;

/**
 * Local-only push boundary for the patient appointment slice.
 *
 * <p>No APNs/FCM provider is part of this delivery, so this adapter accepts the
 * single generic notice and does not open a network connection, log the provider
 * token or emit clinical detail. It exists to keep the dispatch invariant
 * testable without a deployment decision. The service's {@code ..._SENT} audit
 * facts therefore record a dispatch attempt, not a provider receipt; a real
 * gateway adapter must replace this bean before patient enablement.</p>
 */
@Component
public class PatientAppointmentPushGateway implements PushGateway {

    /** {@inheritDoc} */
    @Override
    public Outcome send(String token, GenericPushNotice notice) {
        return Outcome.DELIVERED;
    }
}
