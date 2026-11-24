package com.aurevia.lab.accession;

/**
 * One immutable step in an accession's history (LAB-01, OPS-02).
 *
 * @param id              immutable event identifier
 * @param accessionId     specimen the event describes
 * @param accessionVersion revision the event commits with
 * @param action          stable action key
 * @param fromState       prior state, or {@code null} on creation
 * @param toState         resulting state
 * @param reason          recorded reason for a rejection, otherwise {@code null}
 * @param matchedPatientId patient identity checked at collection or receipt
 * @param occurredAt      ISO-8601 event instant
 * @return this record
 */
public record AccessionEvent(String id, String accessionId, int accessionVersion, String action,
                             String fromState, String toState, String reason,
                             String matchedPatientId, String occurredAt) {
}
