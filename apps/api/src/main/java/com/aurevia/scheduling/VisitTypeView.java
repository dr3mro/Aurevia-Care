package com.aurevia.scheduling;

/**
 * Visit type projection. The duration belongs to the visit type, so every slot
 * length comes from one tenant-approved source.
 *
 * @param code            tenant-local visit type code
 * @param durationMinutes appointment length in minutes
 * @return this record
 */
public record VisitTypeView(String code, int durationMinutes) {
}
