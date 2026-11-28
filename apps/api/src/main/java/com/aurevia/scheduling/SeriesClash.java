package com.aurevia.scheduling;

/**
 * One rejected occurrence of a series and the appointment that already owns its
 * protected slot.
 *
 * @param occurrence    one-based position of the clashing occurrence in the series
 * @param appointmentId immutable id of the conflicting appointment, or {@code null}
 *                      when the conflict was detected by the database at commit time
 * @param startsAt      UTC start of the clashing occurrence
 * @return this record
 */
public record SeriesClash(int occurrence, String appointmentId, String startsAt) {
}
