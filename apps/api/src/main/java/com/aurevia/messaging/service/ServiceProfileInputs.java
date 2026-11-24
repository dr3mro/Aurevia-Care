package com.aurevia.messaging.service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.units.MessagingInputs;
import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;

/**
 * Pure, total validation of every MSG-06 publish field (ADM-01, UX-01).
 *
 * <p>Every method either returns a normalized value or raises a structured
 * {@code 422} before any domain or store call. A malformed zone, window,
 * identifier or piece of guidance copy can never reach a durable profile.</p>
 */
public final class ServiceProfileInputs {

    /** Detects an embedded long digit run that could be an identifier (OPS-02). */
    private static final Pattern PHI_SHAPED = Pattern.compile("\\d{8,}");

    private ServiceProfileInputs() {
    }

    /**
     * Parse and validate an IANA time zone, rejecting fixed offsets.
     *
     * @param value supplied zone identifier
     * @return the validated zone
     * @throws ApiException when absent, malformed or a fixed offset
     */
    public static ZoneId zone(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_ZONE", "timeZone must be a non-blank IANA identifier");
        }
        try {
            ZoneId parsed = ZoneId.of(value.trim());
            if (parsed instanceof ZoneOffset) {
                throw new ApiException(422, "INVALID_ZONE", "timeZone must be an IANA region, not a fixed offset");
            }
            return parsed;
        } catch (DateTimeException exception) {
            throw new ApiException(422, "INVALID_ZONE", "timeZone must be a valid IANA identifier");
        }
    }

    /**
     * Parse one service window in {@code DAY|HH:mm|HH:mm} form.
     *
     * @param value supplied window text
     * @return the validated window
     * @throws ApiException when any part is malformed or the times are equal
     */
    public static ServiceWindow window(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_WINDOW", "window must be DAY|HH:mm|HH:mm");
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) {
            throw new ApiException(422, "INVALID_WINDOW", "window must be DAY|HH:mm|HH:mm");
        }
        try {
            DayOfWeek day = DayOfWeek.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
            LocalTime start = LocalTime.parse(parts[1].trim());
            LocalTime end = LocalTime.parse(parts[2].trim());
            if (start.equals(end)) {
                throw new ApiException(422, "INVALID_WINDOW", "window start and end must differ");
            }
            return new ServiceWindow(day, start, end);
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_WINDOW", "window must have a valid day and times");
        }
    }

    /**
     * Parse and validate the complete service-window list.
     *
     * <p>At least one window is mandatory: a published profile with no windows
     * would report no next handling time and break the after-hours guidance
     * required by MSG-06.</p>
     *
     * @param values supplied window strings
     * @return an immutable list of validated windows
     * @throws ApiException when the list is absent, empty or any entry is malformed
     */
    public static List<ServiceWindow> windows(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new ApiException(422, "INVALID_WINDOWS", "at least one service window is required");
        }
        List<ServiceWindow> result = new ArrayList<>();
        for (String value : values) {
            result.add(window(value));
        }
        return List.copyOf(result);
    }

    /**
     * Validate a positive, bounded minute value (ARC-04 business input).
     *
     * @param value supplied value, possibly absent
     * @param field field name used in the error code
     * @param max   maximum accepted value
     * @return the validated value
     * @throws ApiException when absent, non-positive or above the maximum
     */
    public static int minutes(Number value, String field, int max) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), field + " is required");
        }
        long parsed = value.longValue();
        if (parsed < 1 || parsed > max) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 1-" + max + " minutes");
        }
        return (int) parsed;
    }

    /**
     * Validate a non-negative version precondition. Zero means "no published
     * version yet" and is the only valid precondition for a unit's first publish;
     * reuse of a minute validator here would make every new unit unpublishable.
     *
     * @param value supplied value, possibly absent
     * @param field field name used in the error code
     * @return the validated version
     * @throws ApiException when absent, negative or above the int range
     */
    public static int version(Number value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), field + " is required");
        }
        long parsed = value.longValue();
        if (parsed < 0 || parsed > Integer.MAX_VALUE) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 0-" + Integer.MAX_VALUE);
        }
        return (int) parsed;
    }

    /**
     * Validate a bounded list of non-blank text values.
     *
     * @param values    supplied list
     * @param field     field name used in the error code
     * @param maxItems  maximum number of entries
     * @param maxLength maximum length of each entry
     * @return an immutable, trimmed copy
     * @throws ApiException when the list is absent, empty, oversized or malformed
     */
    public static List<String> list(List<String> values, String field, int maxItems, int maxLength) {
        if (values == null || values.isEmpty() || values.size() > maxItems) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must have 1-" + maxItems + " entries");
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(safeText(value, field, maxLength));
        }
        return List.copyOf(result);
    }

    /**
     * Validate bounded, approved static guidance copy in supported languages.
     *
     * <p>Guidance may never carry a patient identifier or other PHI-shaped token;
     * only site-approved static text is accepted (MSG-06, OPS-02).</p>
     *
     * @param values supplied localized text entries
     * @return an immutable, trimmed copy
     * @throws ApiException when absent, malformed or unsafe
     */
    public static List<LocalizedText> instructions(List<LocalizedText> values) {
        if (values == null || values.isEmpty() || values.size() > 4) {
            throw new ApiException(422, "INVALID_URGENTINSTRUCTIONS", "urgentInstructions must have 1-4 entries");
        }
        List<LocalizedText> result = new ArrayList<>();
        for (LocalizedText value : values) {
            if (value == null) {
                throw new ApiException(422, "INVALID_URGENTINSTRUCTIONS", "urgentInstructions entry is required");
            }
            String language = MessagingInputs.text(value.language(), "language", 8);
            if (!"en".equals(language) && !"ar".equals(language)) {
                throw new ApiException(422, "INVALID_LANGUAGE", "language must be en or ar");
            }
            result.add(new LocalizedText(language, safeText(value.value(), "urgentInstructions", 500)));
        }
        boolean hasEnglish = result.stream().anyMatch(entry -> "en".equals(entry.language()));
        boolean hasArabic = result.stream().anyMatch(entry -> "ar".equals(entry.language()));
        if (!hasEnglish || !hasArabic) {
            throw new ApiException(422, "INVALID_URGENTINSTRUCTIONS",
                    "urgentInstructions must include both en and ar");
        }
        return List.copyOf(result);
    }

    /**
     * Validate required text that must not contain a PHI-shaped digit run.
     *
     * @param value supplied text
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return the trimmed text
     * @throws ApiException when absent, too long or PHI-shaped
     */
    public static String safeText(String value, String field, int max) {
        String normalized = MessagingInputs.text(value, field, max);
        if (PHI_SHAPED.matcher(normalized).find()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must not contain an identifier-shaped value");
        }
        return normalized;
    }

    /**
     * Parse an effective-from instant.
     *
     * @param value supplied ISO-8601 instant
     * @param field field name used in the error code
     * @return the parsed instant
     * @throws ApiException when absent or malformed
     */
    public static Instant instant(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), field + " is required");
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), field + " must be an ISO-8601 instant");
        }
    }
}
