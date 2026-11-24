package com.aurevia.patient;

/**
 * Effective projection of one patient record in the active organization (IAM-03).
 *
 * @param id                opaque patient identifier
 * @param version           current append-only version
 * @param mrn               issuer-scoped medical record number
 * @param mrnIssuer         issuer of the MRN
 * @param displayName       original spelling, preserved for display
 * @param normalizedName    normalized form used only for search
 * @param dob               civil date of birth, or {@code null} when unknown
 * @param dobUnknownReason  coded reason when the date of birth is unknown
 * @param language          patient language
 * @param contacts          contact records as JSON
 * @param identifiers       issuer-scoped identity records as JSON
 * @param consents          consent records as JSON
 * @return this record
 */
public record Patient(String id, int version, String mrn, String mrnIssuer, String displayName,
                      String normalizedName, String dob, String dobUnknownReason, String language,
                      String contacts, String identifiers, String consents) {
}
