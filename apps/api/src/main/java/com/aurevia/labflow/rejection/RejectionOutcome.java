package com.aurevia.labflow.rejection;

import com.aurevia.lab.accession.AccessionState;

/**
 * Result of rejecting a specimen, including who was notified (LAB-01).
 *
 * @param specimenId        opaque identifier of the rejected specimen
 * @param state             resulting lifecycle state
 * @param version           new optimistic-concurrency version
 * @param reason            retained reason, with detail for the {@code other} reason
 * @param notification      retained ordering-clinician notification target
 * @param clinicianNotified whether the ordering clinician was notified
 * @param patientNotified   whether the patient was notified under policy
 * @return this record
 */
public record RejectionOutcome(String specimenId, AccessionState state, int version, String reason,
                               String notification, boolean clinicianNotified, boolean patientNotified) {
}
