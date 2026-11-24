package com.aurevia.labflow.rejection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Policy gate for notifying a patient about a rejected specimen (LAB-01, PAT-02).
 *
 * <p>The ordering clinician is always notified. Patient notification is a
 * separate, conservative default-off decision driven by configuration so a site
 * must explicitly enable it; the service consults this gate for every rejection
 * and never derives patient contact permission from the request alone.</p>
 */
@Component
public class RejectionNotificationPolicy {

    private final boolean patientNotificationEnabled;

    /**
     * Create the policy from the configured switch.
     *
     * @param patientNotificationEnabled whether patient notification is enabled for this site
     */
    public RejectionNotificationPolicy(
            @Value("${orevia.lab.rejection.notify-patient:false}") boolean patientNotificationEnabled) {
        this.patientNotificationEnabled = patientNotificationEnabled;
    }

    /**
     * Decide whether the patient may be notified of a rejection.
     *
     * @return {@code true} only when site policy has enabled patient notification
     */
    public boolean mayNotifyPatient() {
        return patientNotificationEnabled;
    }
}
