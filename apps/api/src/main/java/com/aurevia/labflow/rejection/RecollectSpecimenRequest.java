package com.aurevia.labflow.rejection;

/**
 * Immutable recollection command for a rejected specimen (LAB-01, ARC-03).
 *
 * <p>A recollection mints a fresh specimen identifier but never a fresh order:
 * the new accession inherits the source order and patient of the specimen it
 * replaces, so the two specimens stay on one order.</p>
 *
 * @param accessionNo     new issuer-scoped accession number for the recollection
 * @param accessionIssuer issuer of the new accession number
 * @param expectedVersion version of the rejected specimen read by the caller
 * @return this record
 */
public record RecollectSpecimenRequest(String accessionNo, String accessionIssuer, int expectedVersion) {
}
