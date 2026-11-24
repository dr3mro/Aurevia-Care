package com.aurevia.lab.accession;

/**
 * The current projection of one tenant-owned accession (LAB-01, ARC-01).
 *
 * <p>The source order is retained as a bounded reference because the order
 * aggregate belongs to a separate exchange story; this slice never invents an
 * order record or grants general chart access through the reference.</p>
 *
 * @param id                    opaque specimen identifier
 * @param version               optimistic-concurrency version
 * @param accessionNo           issuer-scoped accession number
 * @param accessionIssuer       issuer of the accession number
 * @param patientId             verified patient record
 * @param sourceOrderRef        bounded source order reference
 * @param state                 current lifecycle state
 * @param specimenType          specimen type code, once collected
 * @param siteCode              collection site code, once collected
 * @param collector             recorded collector, once collected
 * @param collectedAt           ISO-8601 collection instant
 * @param receivedAt            ISO-8601 receipt instant
 * @param quality               receipt quality code
 * @param rejectionReason       retained reason, once rejected
 * @param rejectionNotification retained notification, once rejected
 * @param replacesAccessionId   prior specimen identifier for a recollection
 * @return this record
 */
public record LabAccession(String id, int version, String accessionNo, String accessionIssuer,
                           String patientId, String sourceOrderRef, AccessionState state,
                           String specimenType, String siteCode, String collector,
                           String collectedAt, String receivedAt, String quality,
                           String rejectionReason, String rejectionNotification,
                           String replacesAccessionId) {
}
