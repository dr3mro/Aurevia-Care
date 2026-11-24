package com.aurevia.labflow.results;

/**
 * Command for verification, correction and withdrawal of one report (LAB-02).
 *
 * <p>The verifier is derived from the authenticated context, never from this
 * command. The patient and accession identity are re-presented so every
 * transition repeats the label match, and a reason is mandatory for a
 * correction or a withdrawal.</p>
 *
 * @param patientId       patient identity presented with the report
 * @param accessionNo     accession number presented with the report
 * @param accessionIssuer accession issuer presented with the report
 * @param expectedVersion report version read by the caller
 * @param owner           named result owner
 * @param queue           coverage queue
 * @param reason          reason for a correction or withdrawal
 * @return this record
 */
public record ResultDecisionRequest(String patientId, String accessionNo, String accessionIssuer,
                                    int expectedVersion, String owner, String queue, String reason) {
}
