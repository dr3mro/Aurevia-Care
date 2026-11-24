package com.aurevia.labflow.collection;

/**
 * Print-ready label data for one collected specimen (LAB-01).
 *
 * <p>Only identifiers and the collection instant are returned; no clinical
 * result or free-text narrative is placed on a physical label.</p>
 *
 * @param accessionNo     issuer-scoped accession number
 * @param accessionIssuer organization that issued the accession number
 * @param patientName     patient display name for the label
 * @param patientMrn      issuer-scoped patient identifier
 * @param patientMrnIssuer issuer of the patient identifier
 * @param specimenType    collected specimen type code
 * @param collectedAt     collection time as recorded on the specimen
 * @return this record
 */
public record SpecimenLabelData(String accessionNo, String accessionIssuer, String patientName,
                                String patientMrn, String patientMrnIssuer, String specimenType,
                                String collectedAt) {
}
