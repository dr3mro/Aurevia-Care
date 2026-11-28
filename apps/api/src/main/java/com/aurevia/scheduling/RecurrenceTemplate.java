package com.aurevia.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.aurevia.api.ApiException;

/**
 * A weekly recurrence pattern for an appointment series.
 *
 * <p>The pattern is civil: a start local date and clock time in the facility's
 * IANA zone, stepped by a whole number of weeks. Each occurrence is resolved
 * back to an instant through {@link java.time.LocalDateTime#atZone(java.time.ZoneId)},
 * so a daylight-saving transition changes the UTC offset but never the local
 * clock time the patient and clinician see. The pattern terminates on either a
 * fixed occurrence count or an inclusive local end date, never both.</p>
 *
 * @param intervalWeeks   weeks between consecutive occurrences, 1-4
 * @param occurrenceCount fixed number of occurrences, or {@code null} for an end date
 * @param endDate         inclusive local end date, or {@code null} for a fixed count
 * @param startDate       local date of the first occurrence
 * @param localTime       local clock time shared by every occurrence
 * @param timeZone        facility IANA zone used to resolve local times
 * @return this record
 */
public record RecurrenceTemplate(int intervalWeeks, Integer occurrenceCount, LocalDate endDate,
                                 LocalDate startDate, LocalTime localTime, ZoneId timeZone) {

    private static final int MAX_OCCURRENCES = 52;

    /**
     * Build a validated recurrence from a series command.
     *
     * @param intervalWeeks   weeks between occurrences, already range checked by the caller
     * @param occurrenceCount fixed count, or {@code null} when an end date is supplied
     * @param endDate         ISO-8601 local end date, or {@code null}/blank
     * @param firstStart      instant of the first occurrence
     * @param timeZoneId      facility IANA time-zone name
     * @return the validated template
     * @throws ApiException when the end date is malformed, not after the first
     *                      occurrence, too far ahead, or no termination is present
     */
    public static RecurrenceTemplate of(int intervalWeeks, Integer occurrenceCount, String endDate,
                                        Instant firstStart, String timeZoneId) {
        ZoneId zone = SchedulingInputs.zone(timeZoneId);
        LocalDateTime firstLocal = firstStart.atZone(zone).toLocalDateTime();
        LocalDate end = null;
        if (endDate != null && !endDate.isBlank()) {
            try {
                end = LocalDate.parse(endDate.trim());
            } catch (DateTimeParseException exception) {
                throw new ApiException(422, "INVALID_END_DATE", "endDate must be an ISO-8601 local date");
            }
            if (!end.isAfter(firstLocal.toLocalDate())) {
                throw new ApiException(422, "INVALID_END_DATE", "endDate must follow the first occurrence");
            }
            if (end.isAfter(firstLocal.toLocalDate().plusDays(7L * intervalWeeks * MAX_OCCURRENCES))) {
                throw new ApiException(422, "END_DATE_TOO_FAR", "the series exceeds the maximum span");
            }
        }
        if (occurrenceCount == null && end == null) {
            throw new ApiException(422, "INVALID_OCCURRENCES", "either occurrences or endDate is required");
        }
        return new RecurrenceTemplate(intervalWeeks, occurrenceCount, end, firstLocal.toLocalDate(),
                firstLocal.toLocalTime(), zone);
    }

    /**
     * Expand the pattern into UTC occurrence starts in ascending order.
     *
     * @return the immutable list of occurrence instants
     */
    public List<Instant> occurrenceInstants() {
        List<Instant> starts = new ArrayList<>();
        LocalDate date = startDate;
        int index = 0;
        while (occurrenceCount == null ? !date.isAfter(endDate) : index < occurrenceCount) {
            starts.add(date.atTime(localTime).atZone(timeZone).toInstant());
            date = date.plusDays(7L * intervalWeeks);
            index++;
        }
        return List.copyOf(starts);
    }
}
