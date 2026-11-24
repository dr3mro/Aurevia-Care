package com.aurevia.scheduling;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.FacilityCode;

/**
 * Bounded input validation shared by the scheduling commands.
 *
 * <p>Everything here is pure and total: each method either returns a normalized
 * value or raises a {@code 422} with a stable code. Validation happens before any
 * SQL so a malformed identifier can never reach a cast.</p>
 */
final class SchedulingInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_REASON = 200;

    private SchedulingInputs() {
    }

    /**
     * Validate an opaque tenant-owned identifier.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the value unchanged
     * @throws ApiException when the value is not a UUID
     */
    static String uuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a UUID");
        }
        return value;
    }

    /**
     * Validate a tenant-local code such as a facility, visit type or resource code.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the value unchanged
     * @throws ApiException when the value is not a bounded upper-case code
     */
    static String code(String value, String field) {
        try {
            return FacilityCode.facilityCode(value).value();
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    FacilityCode.INVALID_MESSAGE);
        }
    }

    /**
     * Parse an ISO-8601 instant.
     *
     * @param value value supplied by the caller
     * @return the parsed instant
     * @throws ApiException when the value is absent or not an instant
     */
    static Instant instant(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_START", "startsAt must be an ISO-8601 instant");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_START", "startsAt must be an ISO-8601 instant");
        }
    }

    /**
     * Normalize an optional recorded reason.
     *
     * @param value value supplied by the caller, possibly {@code null} or blank
     * @return the trimmed reason, or {@code null} when nothing was recorded
     * @throws ApiException when the reason exceeds the stored bound
     */
    static String reason(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized != null && normalized.length() > MAX_REASON) {
            throw new ApiException(422, "INVALID_REASON", "reason must be 1-200 characters");
        }
        return normalized;
    }

    /**
     * Validate a required human-readable text field.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return the trimmed text
     * @throws ApiException when the value is absent, blank or too long
     */
    static String text(String value, String field, int max) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > max) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 1-" + max + " characters");
        }
        return normalized;
    }

    /**
     * Parse an IANA time zone.
     *
     * @param value zone name from the facility record
     * @return the resolved zone
     * @throws ApiException when the zone name is not a valid IANA identifier
     */
    static ZoneId zone(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new ApiException(422, "INVALID_TIME_ZONE", "timeZone must be an IANA zone");
        }
    }

    /**
     * Parse a local window boundary.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the parsed local time
     * @throws ApiException when the value is absent or not {@code HH:mm}
     */
    static LocalTime localTime(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a local time such as 09:00");
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a local time such as 09:00");
        }
    }

    /**
     * Validate a weekday number.
     *
     * @param weekday ISO-8601 day of week where 1 is Monday
     * @return the weekday
     * @throws ApiException when the value is outside 1-7
     */
    static int weekday(Integer weekday) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            throw new ApiException(422, "INVALID_WEEKDAY", "weekday must be 1 (Monday) to 7 (Sunday)");
        }
        return weekday;
    }

    /**
     * Validate a visit duration.
     *
     * @param durationMinutes appointment length in minutes
     * @return the duration
     * @throws ApiException when the value is outside 5-480
     */
    static int duration(Integer durationMinutes) {
        if (durationMinutes == null || durationMinutes < 5 || durationMinutes > 480) {
            throw new ApiException(422, "INVALID_DURATION", "durationMinutes must be 5-480");
        }
        return durationMinutes;
    }

    /**
     * Opaque identifier for a new record.
     *
     * @return random UUID text
     */
    static String newId() {
        return UUID.randomUUID().toString();
    }
}
