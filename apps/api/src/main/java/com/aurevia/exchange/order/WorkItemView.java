package com.aurevia.exchange.order;

import java.time.Instant;

/**
 * Bounded work item projected to the receiving organization.
 *
 * <p>Only exchange-specific fields are projected. The sender patient reference
 * and its issuer-scoped MRN let the receiver verify a local patient through a
 * checked identifier match with an explicit attestation; they are not keys into
 * any sender chart and grant no general chart access.</p>
 *
 * @param id                  work item identifier in the receiving organization
 * @param senderOrg           sending organization
 * @param destinationOrg      receiving organization that owns the work item projection
 * @param orderRef            opaque sender order identifier
 * @param patientRef          opaque sender patient reference, not a receiver chart key
 * @param patientMrn          issuer-scoped MRN of the referred patient, for local verification
 * @param mrnIssuer           issuer of that MRN
 * @param localPatientId      locally verified patient identifier, or {@code null}
 * @param patientVerified     whether a receiver clinician bound a local patient
 * @param priority            released priority
 * @param clinicalQuestion    released clinical question
 * @param workQueue           destination work queue
 * @param fingerprint         fingerprint of the exact released snapshot
 * @param state               {@code awaiting_local_patient} or {@code ready}
 * @param createdAt           projection instant
 * @return this record
 */
public record WorkItemView(String id, String senderOrg, String destinationOrg, String orderRef,
                           String patientRef, String patientMrn, String mrnIssuer,
                           String localPatientId, boolean patientVerified, String priority,
                           String clinicalQuestion, String workQueue, String fingerprint,
                           String state, Instant createdAt) {
}
