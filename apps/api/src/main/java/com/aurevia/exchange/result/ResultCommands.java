package com.aurevia.exchange.result;

/**
 * Command payloads for the XCH-04 result-routing slice (S-G2-04).
 *
 * <p>Commands are intentionally narrow and contain only values that the service
 * validates at the trust boundary before any persistence is attempted.</p>
 */
public final class ResultCommands {

    private ResultCommands() {
    }

    /**
     * Register the identifiers a released order is expected to produce.
     *
     * <p>This is the synthetic stand-in for the order projection owned by the
     * exchange stories that are not present on this branch.</p>
     *
     * @param orderId       tenant-local order identifier
     * @param patientId     local patient identifier
     * @param sourceId      expected result issuer
     * @param accessionId   expected issuer-scoped accession
     * @param destinationId expected receiving unit
     * @param owner         named owner for routed results
     * @param coverageQueue coverage queue for routed results
 * @return this record
 */
public record RegisterOrderRequest(String orderId, String patientId, String sourceId,
                                       String accessionId, String destinationId,
                                       String owner, String coverageQueue) {
    }

    /**
     * Import one inbound result revision.
     *
     * @param orderId           tenant-local order identifier claimed by the sender
     * @param patientId         claimed local patient identifier
     * @param sourceId          actual result issuer
     * @param accessionId       actual issuer-scoped accession
     * @param destinationId     actual receiving unit
     * @param kind              {@code FINAL}, {@code CORRECTED} or {@code CRITICAL}
     * @param supersedesVersion prior version corrected by this import, or null
     * @param effectiveAt       clinical effective instant in ISO-8601
     * @param checksum          checksum of the retained synthetic payload
 * @return this record
 */
public record ImportResultRequest(String orderId, String patientId, String sourceId,
                                      String accessionId, String destinationId, String kind,
                                      Integer supersedesVersion, String effectiveAt,
                                      String checksum) {
    }
}
