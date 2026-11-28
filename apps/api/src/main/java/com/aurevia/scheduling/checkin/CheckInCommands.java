package com.aurevia.scheduling.checkin;

import java.util.List;

/**
 * Request bodies for check-in commands, kept as small typed records at the
 * web boundary. Each record carries only the fields its command needs.
 */
public final class CheckInCommands {

    private CheckInCommands() {
    }

    /**
 * @return this record
     */
public record VerifyIdentity(String appointmentId, List<String> identifiers, String method,
                                 String alternateCode, String alternateReason) {

        /**
         * Copy the caller's list so a command cannot be mutated after submission.
         *
         * @throws NullPointerException when identifiers is null
         */
        public VerifyIdentity {
            identifiers = identifiers == null ? List.of() : List.copyOf(
                    identifiers.stream().filter(java.util.Objects::nonNull).toList());
        }

        /**
         * @return the immutable identifier categories
         */
        @Override
        public List<String> identifiers() {
            return List.copyOf(identifiers);
        }
    }

    /**
     * @param assigneeSubject subject of the active member to assign
     * @return this record
     */
    public record Assign(String assigneeSubject) {
    }

    /**
     * @param toSubject subject of the active member receiving the handoff
     * @param reason    recorded reason for the transfer
 * @return this record
     */
public record Handoff(String toSubject, String reason) {
    }

    /**
     * @param facilityCode facility receiving the unscheduled arrival
     * @param patientId    verified patient record identifier
     * @param reason       why the arrival has no appointment
 * @return this record
     */
public record Intake(String facilityCode, String patientId, String reason) {
    }
}
