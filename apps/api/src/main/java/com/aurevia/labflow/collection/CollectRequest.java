package com.aurevia.labflow.collection;

/**
 * Immutable collection command for one accessioned specimen (LAB-01).
 *
 * <p>The presented patient and accession identity are carried so the service can
 * confirm the bedside match before any specimen detail is stored. Organization,
 * actor and author are never accepted from the client.</p>
 *
 * @param patientId       patient identity presented at the bedside
 * @param accessionNo     accession number presented at the bedside
 * @param accessionIssuer accession issuer presented at the bedside
 * @param specimenType    bounded specimen type code
 * @param siteCode        bounded collection site code
 * @param collector       recorded collector
 * @param collectedAt     ISO-8601 collection instant
 * @param quality         bounded specimen quality code
 * @param expectedVersion version read by the caller
 * @return this record
 */
public record CollectRequest(String patientId, String accessionNo, String accessionIssuer,
                             String specimenType, String siteCode, String collector,
                             String collectedAt, String quality, int expectedVersion) {
}
