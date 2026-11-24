package com.aurevia.labflow.release;

import java.time.Duration;
import java.time.Instant;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.release.ReleaseKind;

/**
 * The facility's patient release policy for one issued laboratory report (XCH-06, PAT-02, ADM-01).
 *
 * <p>The clinician receives an issued report immediately. Patient access is governed
 * by the configured kind: {@link com.aurevia.exchange.release.ReleaseKind#IMMEDIATE}
 * opens at once, {@link com.aurevia.exchange.release.ReleaseKind#TIMED} opens only
 * after the configured delay, and {@link com.aurevia.exchange.release.ReleaseKind#HOLD}
 * stays closed until an authorized reviewer lifts it. A hold always requires a positive
 * review deadline so it can never open by elapsed time alone. In a deployed site these
 * values come from an approved, effective-dated configuration record; this slice carries
 * them on the command because the configuration store is owned by a separate task.</p>
 *
 * @param policyVersion approved release policy version applied to the decision
 * @param patientKind   patient visibility policy
 * @param patientDelay  delay after which a timed release opens, or the review deadline of a hold
 * @return this policy
 */
public record FacilityReleasePolicy(int policyVersion, ReleaseKind patientKind, Duration patientDelay) {

    /**
     * Validate the policy and normalise an absent delay to zero.
     *
     * @throws ApiException {@code 422} when the version, kind or delay is invalid
     */
    public FacilityReleasePolicy {
        if (policyVersion < 1) {
            throw new ApiException(422, "INVALID_POLICY_VERSION", "policy version must be positive");
        }
        if (patientKind == null) {
            throw new ApiException(422, "INVALID_RELEASE_KIND", "a patient release kind is required");
        }
        patientDelay = patientDelay == null ? Duration.ZERO : patientDelay;
        if (patientDelay.isNegative()) {
            throw new ApiException(422, "INVALID_RELEASE_DELAY", "the release delay cannot be negative");
        }
        if (patientKind == ReleaseKind.HOLD && patientDelay.isZero()) {
            throw new ApiException(422, "INVALID_RELEASE_DELAY", "a held release requires a review deadline");
        }
    }

    /**
     * Compute the instant from which patient access may open.
     *
     * @param now current instant from the injected clock
     * @return the effective instant
     */
    public Instant patientEffectiveFrom(Instant now) {
        return now.plus(patientDelay);
    }

    /**
     * Whether patient access needs an explicit reviewer decision.
     *
     * @return {@code true} only for a held release
     */
    public boolean reviewRequired() {
        return patientKind == ReleaseKind.HOLD;
    }
}
