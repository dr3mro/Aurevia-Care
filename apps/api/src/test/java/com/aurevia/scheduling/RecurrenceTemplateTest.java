package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * CLN-01 recurrence expansion. Occurrences are civil dates plus the facility
 * local clock time, so a daylight-saving change never shifts the local time.
 */
class RecurrenceTemplateTest {

    private static final String ZONE = "America/New_York";
    private static final Instant FIRST = Instant.parse("2027-03-07T14:00:00Z");

    @Test
    void keepsTheLocalClockTimeAcrossTheSpringForward() {
        RecurrenceTemplate template = RecurrenceTemplate.of(1, 3, null, FIRST, ZONE);
        List<Instant> starts = template.occurrenceInstants();
        assertEquals(List.of(Instant.parse("2027-03-07T14:00:00Z"),
                Instant.parse("2027-03-14T13:00:00Z"),
                Instant.parse("2027-03-21T13:00:00Z")), starts);
        assertEquals(LocalTime.of(9, 0), template.localTime());
        assertEquals(ZoneId.of(ZONE), template.timeZone());
        assertTrue(starts.stream().allMatch(start ->
                start.atZone(ZoneId.of(ZONE)).toLocalTime().equals(LocalTime.of(9, 0))));
    }

    @Test
    void repeatsEveryNWeeks() {
        List<Instant> starts = RecurrenceTemplate.of(2, 3, null, FIRST, ZONE).occurrenceInstants();
        assertEquals(List.of(Instant.parse("2027-03-07T14:00:00Z"),
                Instant.parse("2027-03-21T13:00:00Z"),
                Instant.parse("2027-04-04T13:00:00Z")), starts);
    }

    @Test
    void stopsAtAnEndLocalDate() {
        List<Instant> starts = RecurrenceTemplate.of(1, null, "2027-03-21", FIRST, ZONE)
                .occurrenceInstants();
        assertEquals(3, starts.size());
        assertEquals(Instant.parse("2027-03-21T13:00:00Z"), starts.get(2));
    }

    @Test
    void rejectsAnEndDateOnOrBeforeTheFirstOccurrence() {
        ApiException sameDay = assertThrows(ApiException.class,
                () -> RecurrenceTemplate.of(1, null, "2027-03-07", FIRST, ZONE));
        assertEquals(422, sameDay.status());
        assertEquals("INVALID_END_DATE", sameDay.code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> RecurrenceTemplate.of(1, null, "2027-03-01", FIRST, ZONE)).status());
    }

    @Test
    void rejectsAnUnparseableEndDate() {
        ApiException failure = assertThrows(ApiException.class,
                () -> RecurrenceTemplate.of(1, null, "not-a-date", FIRST, ZONE));
        assertEquals(422, failure.status());
        assertEquals("INVALID_END_DATE", failure.code());
    }

    @Test
    void rejectsAnUnboundedEndDate() {
        ApiException failure = assertThrows(ApiException.class,
                () -> RecurrenceTemplate.of(1, null, "2029-01-01", FIRST, ZONE));
        assertEquals(422, failure.status());
        assertEquals("END_DATE_TOO_FAR", failure.code());
    }

    @Test
    void rejectsAMissingTermination() {
        ApiException failure = assertThrows(ApiException.class,
                () -> RecurrenceTemplate.of(1, null, "  ", FIRST, ZONE));
        assertEquals(422, failure.status());
        assertEquals("INVALID_OCCURRENCES", failure.code());
    }
}
