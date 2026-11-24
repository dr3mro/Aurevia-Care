package com.aurevia.labflow.rejection;

/**
 * Immutable rejection command for one accessioned specimen (LAB-01, ARC-03).
 *
 * <p>The ordering clinician and the patient-notification request are carried so
 * the service can notify the right parties. Organization and actor are never
 * accepted from the client; they always come from the active tenant context.</p>
 *
 * @param reason             bounded reason code such as {@code haemolysed} or {@code other}
 * @param detail             bounded free text required when the reason is {@code other}
 * @param orderingClinician  bounded target identifying the ordering clinician to notify
 * @param notifyPatient      whether the patient should be notified when policy allows it
 * @param expectedVersion    version read by the caller
 * @return this record
 */
public record RejectSpecimenRequest(String reason, String detail, String orderingClinician,
                                    boolean notifyPatient, int expectedVersion) {
}
