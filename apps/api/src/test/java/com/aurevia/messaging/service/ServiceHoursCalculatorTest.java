package com.aurevia.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.messaging.service.ServiceProfileContract.ServiceAvailability;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;

/**
 * Unit acceptance for the pure, zone-aware MSG-06 availability calculator.
 *
 * <p>All instants are fixed synthetic values. The spring-forward gap and
 * fall-back overlap cases verify the documented java.time resolution rules.</p>
 */
class ServiceHoursCalculatorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @Test
    void reportsOpenWithinASameDayWindowAndSuppressesNextHandling() {
        ServiceHours hours = hours(UTC, window(DayOfWeek.THURSDAY, 9, 0, 17, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-19T12:00:00Z"));
        assertTrue(result.open());
        assertNull(result.nextHandlingAt());
        assertEquals("UTC", result.timeZone());
    }

    @Test
    void startIsInclusiveAndEndIsExclusive() {
        ServiceHours hours = hours(UTC, window(DayOfWeek.THURSDAY, 9, 0, 17, 0));
        assertTrue(ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-19T09:00:00Z")).open());
        assertFalse(ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-19T17:00:00Z")).open());
    }

    @Test
    void beforeTheWindowReturnsTheNextStartAsAnAbsoluteInstant() {
        ServiceHours hours = hours(UTC, window(DayOfWeek.THURSDAY, 9, 0, 17, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-19T08:00:00Z"));
        assertFalse(result.open());
        assertEquals(Instant.parse("2026-11-19T09:00:00Z"), result.nextHandlingAt());
    }

    @Test
    void aWrapWindowIsOpenAfterMidnightFromThePreviousDayAnchor() {
        ServiceHours hours = hours(UTC, window(DayOfWeek.FRIDAY, 22, 0, 2, 0));
        assertTrue(ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-21T01:00:00Z")).open());
        assertFalse(ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-21T02:00:00Z")).open());
    }

    @Test
    void nextHandlingChoosesTheEarliestFutureStartAcrossTheHorizon() {
        ServiceHours hours = hours(UTC, window(DayOfWeek.FRIDAY, 9, 0, 17, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-16T12:00:00Z"));
        assertFalse(result.open());
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"), result.nextHandlingAt());
    }

    @Test
    void aWindowThatWrapsTheWeekendResumesOnItsDeclaredWeekday() {
        ServiceHours hours = hours(UTC, window(DayOfWeek.SUNDAY, 22, 0, 2, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-21T12:00:00Z"));
        assertEquals(Instant.parse("2026-11-22T22:00:00Z"), result.nextHandlingAt());
    }

    @Test
    void multipleWindowsResolveToTheNearestStart() {
        ServiceHours hours = hours(UTC,
                window(DayOfWeek.THURSDAY, 18, 0, 20, 0),
                window(DayOfWeek.FRIDAY, 9, 0, 17, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-19T12:00:00Z"));
        assertEquals(Instant.parse("2026-11-19T18:00:00Z"), result.nextHandlingAt());
    }

    @Test
    void aUnitWithNoWindowsClosedAndHasNoPromisedHandlingTime() {
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours(UTC), Instant.parse("2026-11-19T12:00:00Z"));
        assertFalse(result.open());
        assertNull(result.nextHandlingAt());
    }

    @Test
    void aPastWindowInEveryCandidateSlotLeavesNoFutureHandlingInstant() {
        ServiceHours hours = new ServiceHours(UTC, List.of());
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-19T12:00:00Z"));
        assertNull(result.nextHandlingAt());
    }

    @Test
    void aLaterCandidateStartIsReplacedByAnEarlierOneOnTheSameDay() {
        ServiceHours hours = hours(UTC,
                window(DayOfWeek.THURSDAY, 18, 0, 20, 0),
                window(DayOfWeek.THURSDAY, 9, 0, 11, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-18T12:00:00Z"));
        assertEquals(Instant.parse("2026-11-19T09:00:00Z"), result.nextHandlingAt());
    }

    @Test
    void aHalfHourZoneProducesTheCorrectAbsoluteStart() {
        ServiceHours hours = hours(KOLKATA, window(DayOfWeek.THURSDAY, 9, 0, 17, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-19T00:00:00Z"));
        assertFalse(result.open());
        assertEquals(Instant.parse("2026-11-19T03:30:00Z"), result.nextHandlingAt());
        assertEquals("Asia/Kolkata", result.timeZone());
    }

    @Test
    void springForwardGapShiftsAnonExistentStartForward() {
        ServiceHours hours = hours(NEW_YORK, window(DayOfWeek.SUNDAY, 2, 30, 4, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-03-08T06:00:00Z"));
        assertFalse(result.open());
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), result.nextHandlingAt());
    }

    @Test
    void fallBackOverlapUsesTheEarlierOffsetForTheRepeatedHour() {
        ServiceHours hours = hours(NEW_YORK, window(DayOfWeek.SUNDAY, 1, 30, 3, 0));
        ServiceAvailability result = ServiceHoursCalculator.calculate(hours, Instant.parse("2026-11-01T04:00:00Z"));
        assertFalse(result.open());
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), result.nextHandlingAt());
    }

    private static ServiceHours hours(ZoneId zone, ServiceWindow... windows) {
        return new ServiceHours(zone, List.of(windows));
    }

    private static ServiceWindow window(DayOfWeek day, int startHour, int startMinute, int endHour, int endMinute) {
        return new ServiceWindow(day, LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }
}
