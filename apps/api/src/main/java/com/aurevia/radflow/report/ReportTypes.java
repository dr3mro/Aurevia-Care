package com.aurevia.radflow.report;

import java.util.Locale;

/**
 * Bounded vocabularies for imaging report delivery and addendum acknowledgment.
 *
 * <p>These enums keep clinically meaningful choices out of free text at the trust
 * boundary. A delivery and a referrer notification are separate facts: producing a
 * signed interpretation does not by itself prove that the referring clinician was
 * notified, so each fact is recorded independently. An unsigned delivery attempt
 * is itself an auditable fact and never becomes a delivery.</p>
 */
public interface ReportTypes {

    /**
     * Acknowledgment state of one addendum revision.
     *
     * <p>An addendum starts {@code OPEN} when it is delivered to the referrer and
     * becomes {@code ACKNOWLEDGED} only when that exact revision is acknowledged.
     * An acknowledged addendum never reopens; a later addendum is a new revision
     * with its own receipt.</p>
     */
    enum AcknowledgmentState {

        /** Delivered but not yet acknowledged by the referred clinician. */
        OPEN,
        /** Acknowledged by the verified referred clinician. */
        ACKNOWLEDGED;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case state name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Immutable facts emitted by the report delivery kernel.
     *
     * <p>Facts carry only identifiers and the actor. No clinical narrative or image
     * data is placed in an event, so an operational fact cannot disclose a
     * protected payload.</p>
     */
    enum ReportEventType {

        /** A signed interpretation was delivered to the referring clinician. */
        INTERPRETATION_DELIVERED,
        /** A signed addendum revision was delivered to the referring clinician. */
        ADDENDUM_DELIVERED,
        /** The referring clinician was notified about a delivered revision. */
        REFERRER_NOTIFIED,
        /** The referring clinician acknowledged one exact addendum revision. */
        ADDENDUM_ACKNOWLEDGED,
        /** An unsigned report was refused before any delivery was recorded. */
        UNSIGNED_REPORT_REFUSED;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case event type name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
