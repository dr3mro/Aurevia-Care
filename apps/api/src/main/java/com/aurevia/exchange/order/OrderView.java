package com.aurevia.exchange.order;

import java.time.Instant;

/**
 * Immutable released order with the identifiers stamped by the exchange (XCH-01).
 *
 * <p>The snapshot fingerprint identifies the exact released content; it is not a
 * cryptographic signature and carries no clinical payload in audit records.</p>
 *
 * @param id                   opaque order identifier
 * @param version              released revision, always one on creation
 * @param patientId            opaque patient identifier of the sending organization
 * @param encounterRef         local encounter reference in the released content
 * @param orderCode            local order code
 * @param priority             released priority
 * @param clinicalQuestion     released clinical question
 * @param destinationOrg       receiving organization
 * @param sourceActor          authenticated subject that released the order
 * @param responsibleClinician responsible clinician of the sending organization
 * @param workQueue            destination work queue
 * @param authorizationId      authorization that permitted the disclosure
 * @param snapshotId           immutable snapshot identifier
 * @param snapshotFingerprint  deterministic fingerprint of the released content
 * @param disclosureId         disclosure ledger identifier
 * @param workItemId           bounded destination work item identifier
 * @param createdAt            release instant
 * @return this record
 */
public record OrderView(String id, int version, String patientId, String encounterRef,
                        String orderCode, String priority, String clinicalQuestion,
                        String destinationOrg, String sourceActor, String responsibleClinician,
                        String workQueue, String authorizationId, String snapshotId,
                        String snapshotFingerprint, String disclosureId, String workItemId,
                        Instant createdAt) {
}
