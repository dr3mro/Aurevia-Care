package com.aurevia.labflow.results;

import java.util.List;

/**
 * Command that captures a report's observations against a matched specimen (LAB-02).
 *
 * <p>The patient and accession identity are presented again so the service can
 * repeat the bedside match before a result is stored. The owner, queue, due
 * window and escalation channel describe the site's approved critical-result
 * policy used when an observation meets a critical bound; they are bounded
 * configuration values, not clinical measurements.</p>
 *
 * @param patientId       patient identity presented with the specimen
 * @param accessionNo     accession number presented with the specimen
 * @param accessionIssuer accession issuer presented with the specimen
 * @param expectedVersion specimen version read by the caller
 * @param owner           approved critical-result owner
 * @param queue           approved coverage queue
 * @param dueAfter        ISO-8601 acknowledgment window, defaulted when absent
 * @param escalation      approved backup escalation channel
 * @param observations    captured observations, at least one
 * @return this record
 */
public record ResultEntryRequest(String patientId, String accessionNo, String accessionIssuer,
                                 int expectedVersion, String owner, String queue, String dueAfter,
                                 String escalation, List<ObservationInput> observations) {

    /**
     * Freeze the observation list so a submitted command cannot change.
     *
     * @return this record with an immutable observation list
     */
    public ResultEntryRequest {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    /**
     * Return the immutable observations.
     *
     * @return observations in capture order
     */
    @Override
    public List<ObservationInput> observations() {
        return List.copyOf(observations);
    }
}
