package com.aurevia.scheduling.followup;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Read-only tenant facts needed to compute a follow-up date (CLN-06).
 *
 * <p>An adapter delegates to the tenant's approved configuration fold and to the
 * scheduling record. Every query is resolved from the active tenant, never from
 * a caller-supplied organization.</p>
 */
public interface FollowUpConfig {

    /**
     * Read the effective approved value of one configuration key.
     *
     * @param key tenant-local configuration key
     * @return the approved value, or empty when none is effective
     */
    Optional<String> setting(String key);

    /**
     * Find an applicable existing follow-up for a patient and visit type.
     *
     * @param patientId  tenant-local patient identifier
     * @param visitType  visit type code
     * @param encounterAt UTC instant of the encounter
     * @return the applicable follow-up, or empty when none qualifies
     */
    Optional<PriorFollowUp> applicablePriorFollowUp(String patientId, String visitType, Instant encounterAt);

    /**
     * Resolve the facility's authoritative IANA time zone.
     *
     * @param facilityCode tenant-local facility code
     * @return the facility time zone
     */
    ZoneId facilityZone(String facilityCode);
}
