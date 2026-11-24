package com.aurevia.lab.accession;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for the laboratory accession slice (LAB-01, IAM-01).
 *
 * <p>Collection and verification are separate duties: a collector can accession
 * and collect but cannot receive or verify a specimen. The policy is local to
 * the slice so no shared role table is widened by this story; site-configurable
 * privileges (ADM-01) remain a later concern.</p>
 */
@Component
public class LabActionPolicy {

    /** Accession a requisition. */
    public static final String ACCESSION_CREATE = "LAB_ACCESSION_CREATE";
    /** Read an accession and its history. */
    public static final String ACCESSION_READ = "LAB_ACCESSION_READ";
    /** Record collection of a specimen. */
    public static final String SPECIMEN_COLLECT = "LAB_SPECIMEN_COLLECT";
    /** Receive and quality-check a specimen. */
    public static final String SPECIMEN_RECEIVE = "LAB_SPECIMEN_RECEIVE";
    /** Reject a specimen with a reason and notification. */
    public static final String SPECIMEN_REJECT = "LAB_SPECIMEN_REJECT";
    /** Create a linked recollection of a rejected specimen. */
    public static final String SPECIMEN_RECOLLECT = "LAB_SPECIMEN_RECOLLECT";

    private static final Set<String> COLLECTOR = Set.of(
            ACCESSION_CREATE, ACCESSION_READ, SPECIMEN_COLLECT);
    private static final Set<String> VERIFIER = Set.of(
            ACCESSION_READ, SPECIMEN_RECEIVE, SPECIMEN_REJECT, SPECIMEN_RECOLLECT);

    /**
     * Decide whether a role may perform a laboratory action.
     *
     * @param action action key such as {@code LAB_SPECIMEN_COLLECT}
     * @param role   role key assigned in the active organization
     * @return {@code true} only for an explicitly allowed duty
     */
    public boolean allows(String action, String role) {
        if (action == null) {
            return false;
        }
        if ("lab_collector".equals(role)) {
            return COLLECTOR.contains(action);
        }
        return "lab_verifier".equals(role) && VERIFIER.contains(action);
    }
}
