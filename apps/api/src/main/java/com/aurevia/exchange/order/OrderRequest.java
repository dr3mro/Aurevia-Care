package com.aurevia.exchange.order;

/**
 * Command to release one clinical order to an approved destination (XCH-01).
 *
 * @param patientId           opaque patient identifier of the sending organization
 * @param encounterRef        local encounter reference carried in the released content
 * @param orderCode           local order code of the sending organization
 * @param priority            one of {@code routine}, {@code urgent} or {@code stat}
 * @param clinicalQuestion    clinical question released with the order
 * @param destinationOrg      opaque identifier of the receiving organization
 * @param purpose             approved business purpose of the disclosure
 * @param authorizationId     identifier of the sender's approved exchange authorization
 * @param responsibleClinician responsible clinician of the sending organization
 * @param workQueue           destination work queue that receives the exchange item
 * @param durationMinutes     authorized validity of the released content
 * @return this record
 */
public record OrderRequest(String patientId, String encounterRef, String orderCode, String priority,
                           String clinicalQuestion, String destinationOrg, String purpose,
                           String authorizationId, String responsibleClinician, String workQueue,
                           Integer durationMinutes) {
}
