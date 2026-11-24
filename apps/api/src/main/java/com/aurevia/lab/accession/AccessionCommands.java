package com.aurevia.lab.accession;

/**
 * Immutable command payloads for the laboratory accession API (LAB-01, ARC-03).
 *
 * <p>Organization, actor and author are never accepted from the client; each
 * record carries only the bounded fields the server validates before opening a
 * tenant-guarded transaction.</p>
 */
public final class AccessionCommands {

    private AccessionCommands() {
    }

    /**
     * Accession a requisition.
     *
     * @param accessionNo     issuer-scoped requisition accession number
     * @param accessionIssuer organization that issued the accession number
     * @param patientId       locally verified patient record
     * @param sourceOrderRef  bounded reference to the source order or requisition
 * @return this record
 */
public record AccessionRequest(String accessionNo, String accessionIssuer,
                                   String patientId, String sourceOrderRef) {
    }

    /**
     * Record collection of a specimen.
     *
     * @param patientId       patient identity presented at the bedside
     * @param accessionNo     accession number presented at the bedside
     * @param accessionIssuer accession issuer presented at the bedside
     * @param specimenType    bounded specimen type code
     * @param siteCode        bounded collection site code
     * @param collector       recorded collector
     * @param collectedAt     ISO-8601 collection instant
     * @param expectedVersion version read by the caller
 * @return this record
 */
public record CollectRequest(String patientId, String accessionNo, String accessionIssuer,
                                 String specimenType, String siteCode, String collector,
                                 String collectedAt, int expectedVersion) {
    }

    /**
     * Receive and quality-check a specimen.
     *
     * @param patientId       patient identity presented at receipt
     * @param accessionNo     accession number presented at receipt
     * @param accessionIssuer accession issuer presented at receipt
     * @param quality         bounded receipt quality code
     * @param receivedAt      ISO-8601 receipt instant
     * @param expectedVersion version read by the caller
 * @return this record
 */
public record ReceiveRequest(String patientId, String accessionNo, String accessionIssuer,
                                 String quality, String receivedAt, int expectedVersion) {
    }

    /**
     * Reject a specimen, retaining its reason and notification.
     *
     * @param reason          bounded rejection reason
     * @param notification    bounded notification target or channel
     * @param expectedVersion version read by the caller
 * @return this record
 */
public record RejectRequest(String reason, String notification, int expectedVersion) {
    }

    /**
     * Create a linked recollection of a rejected specimen.
     *
     * @param accessionNo     new issuer-scoped accession number for the recollection
     * @param accessionIssuer issuer of the new accession number
     * @param expectedVersion version of the rejected specimen read by the caller
 * @return this record
 */
public record RecollectRequest(String accessionNo, String accessionIssuer, int expectedVersion) {
    }
}
