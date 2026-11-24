package com.aurevia.messaging.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.aurevia.messaging.service.ServiceProfileContract.ServiceAvailability;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;

/**
 * Pure, zone-aware availability calculation for MSG-06 (UX-01).
 *
 * <p>The unit's IANA zone is the only authority for wall-clock service hours.
 * Candidate starts are converted to absolute instants with
 * {@code LocalDateTime#atZone(ZoneId)}, which shifts a nonexistent spring-forward
 * time forward and selects the earlier offset at a fall-back overlap. These rules
 * are deterministic and documented for reviewers; the server, never the device,
 * computes the value shown to patients.</p>
 */
public final class ServiceHoursCalculator {

    /** Number of local dates scanned so every weekday and a wrap are covered. */
    private static final int HORIZON_DAYS = 8;

    private ServiceHoursCalculator() {
    }

    /**
     * Evaluate service hours at one instant.
     *
     * @param hours published service hours in the unit's IANA zone
     * @param at    instant to evaluate
     * @return open state and, when closed, the next absolute handling instant
     */
    public static ServiceAvailability calculate(ServiceHours hours, Instant at) {
        ZoneId zone = hours.zone();
        LocalDate localDate = at.atZone(zone).toLocalDate();
        if (isOpen(hours, zone, at, localDate)) {
            return new ServiceAvailability(true, null, zone.getId());
        }
        return new ServiceAvailability(false, nextHandling(hours, zone, at, localDate), zone.getId());
    }

    private static boolean isOpen(ServiceHours hours, ZoneId zone, Instant at, LocalDate localDate) {
        // A window that wraps midnight may have started on the previous local day.
        LocalDate previous = localDate.minusDays(1);
        for (LocalDate anchor : new LocalDate[] { previous, localDate }) {
            for (ServiceWindow window : hours.windows()) {
                if (window.day() != anchor.getDayOfWeek()) {
                    continue;
                }
                Instant start = LocalDateTime.of(anchor, window.start()).atZone(zone).toInstant();
                LocalDate endDate = window.wraps() ? anchor.plusDays(1) : anchor;
                Instant end = LocalDateTime.of(endDate, window.end()).atZone(zone).toInstant();
                if (!at.isBefore(start) && at.isBefore(end)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Instant nextHandling(ServiceHours hours, ZoneId zone, Instant at, LocalDate localDate) {
        Instant best = null;
        for (int offset = 0; offset < HORIZON_DAYS; offset++) {
            LocalDate date = localDate.plusDays(offset);
            for (ServiceWindow window : hours.windows()) {
                if (window.day() != date.getDayOfWeek()) {
                    continue;
                }
                Instant candidate = LocalDateTime.of(date, window.start()).atZone(zone).toInstant();
                if (candidate.isAfter(at) && (best == null || candidate.isBefore(best))) {
                    best = candidate;
                }
            }
        }
        return best;
    }
}
