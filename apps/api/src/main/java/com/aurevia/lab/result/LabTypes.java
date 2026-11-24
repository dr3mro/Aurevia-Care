package com.aurevia.lab.result;

import java.util.Locale;

/**
 * Small value vocabularies shared by the LAB-02 domain kernel.
 *
 * <p>These enums are nested so the slice stays a bounded set of files without
 * weakening type safety: an invalid kind, qualifier or state cannot be
 * represented as an arbitrary string at a trust boundary.</p>
 */
public interface LabTypes {

    /**
     * Interpretation of one observation against an approved reference range or critical policy (LAB-02).
     *
     * <p>A critical flag is derived from a versioned, approved site policy; it is never
     * hard-coded and never inferred from a qualifier or a qualitative value.</p>
     */
    public enum AbnormalFlag {

        /** No abnormality established for the observation. */
        NONE,
        /** The measured value is below the approved reference range. */
        LOW,
        /** The measured value is above the approved reference range. */
        HIGH,
        /** The measured value meets an approved versioned critical policy. */
        CRITICAL;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case flag name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The four LAB-02 observation representations. The kind decides which value field
     * is meaningful and whether the observation can take part in numeric evaluation.
     */
    public enum ObservationKind {

        /** An exact measured number with a unit. */
        NUMERIC,
        /** A measured number bounded by an operator such as less-than. */
        QUALIFIED_NUMERIC,
        /** Free text, including an explicitly empty string. */
        TEXT,
        /** A coded qualitative result from a named code system. */
        CODED;

        /**
         * Return the wire form used by projections and fixtures.
         *
         * @return lower-case kind name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Distinguishes a measured value from an explicitly unknown or not-performed observation (LAB-02, CLN-03).
     *
     * <p>Zero is a measured value with {@code PRESENT}; an unknown reason and an
     * absent observation never collapse to zero or to a normal result.</p>
     */
    public enum ObservationPresence {

        /** A value was measured and is carried by the observation. */
        PRESENT,
        /** The value is explicitly unknown and carries no measured number. */
        UNKNOWN,
        /** The test was not performed and carries no measured number. */
        ABSENT;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case presence name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Operator that bounds a qualified numeric result (LAB-02, XCH-07).
     *
     * <p>A qualifier is presentation and interpretation evidence, not a measurement:
     * a qualified result is preserved and displayed but is never treated as an exact
     * value for range evaluation or trending.</p>
     */
    public enum Qualifier {

        /** No operator; the number is an exact measurement. */
        NONE(""),
        /** The true value is lower than the recorded number. */
        LT("<"),
        /** The true value is greater than the recorded number. */
        GT(">"),
        /** The true value is lower than or equal to the recorded number. */
        LTE("<="),
        /** The true value is greater than or equal to the recorded number. */
        GTE(">=");

        private final String symbol;

        /**
         * Bind the display symbol for this qualifier.
         *
         * @param symbol operator symbol shown to a reader
         */
        Qualifier(String symbol) {
            this.symbol = symbol;
        }

        /**
         * Return the operator symbol shown to a reader.
         *
         * @return the operator symbol, empty for {@code NONE}
         */
        public String symbol() {
            return symbol;
        }

        /**
         * Return the wire form used by projections and fixtures.
         *
         * @return lower-case qualifier name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * LAB-02 report states and their legal transitions.
     *
     * <p>Only issued revisions ({@code FINAL} and {@code CORRECTED}) route results to
     * an owner. A correction or an entered-in-error is a new linked revision: the
     * prior revision is immutable. Verification is distinct from patient release and
     * from clinician acknowledgment.</p>
     */
    public enum VerificationState {

        /** Captured observations awaiting verification; never routed as final. */
        PRELIMINARY,
        /** Verified and issued; route the result to a named owner. */
        FINAL,
        /** A linked issued revision that supersedes an earlier issued revision. */
        CORRECTED,
        /** A withdrawn revision that preserves its predecessor. Terminal. */
        ENTERED_IN_ERROR;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case state name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Decide whether a transition to the requested state is legal.
         *
         * @param target requested next state
         * @return {@code true} when the transition is allowed
         */
        public boolean canTransitionTo(VerificationState target) {
            return switch (this) {
                case PRELIMINARY -> target == FINAL || target == ENTERED_IN_ERROR;
                case FINAL -> target == CORRECTED || target == ENTERED_IN_ERROR;
                case CORRECTED -> target == CORRECTED || target == ENTERED_IN_ERROR;
                case ENTERED_IN_ERROR -> false;
            };
        }

        /**
         * Whether this state routes a result to an owner (XCH-04).
         *
         * @return {@code true} for final and corrected revisions
         */
        public boolean isIssued() {
            return this == FINAL || this == CORRECTED;
        }

        /**
         * Whether no further transition is possible.
         *
         * @return {@code true} only for entered-in-error
         */
        public boolean isTerminal() {
            return this == ENTERED_IN_ERROR;
        }

        /**
         * Whether a reason must be recorded to enter this state.
         *
         * @return {@code true} for corrected and entered-in-error
         */
        public boolean requiresReason() {
            return this == CORRECTED || this == ENTERED_IN_ERROR;
        }
    }

    /**
     * Domain events produced when a laboratory revision is issued (XCH-04, XCH-05).
     *
     * <p>These are immutable facts for a later outbox publisher; this slice does not
     * deliver them to a partner or satisfy a clinical acknowledgment.</p>
     */
    public enum TriggerType {

        /** Route a final result to a named owner and coverage queue. */
        RESULT_ISSUED,
        /** Escalate a critical result with an owner, due time and backstop. */
        CRITICAL_RESULT,
        /** Route a correction and reopen review of the prior revision. */
        RESULT_CORRECTED,
        /** Withdraw future access to an entered-in-error revision. */
        RESULT_WITHDRAWN;

        /**
         * Return the wire form used as the event type namespace.
         *
         * @return lower-case event type name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
