package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Trust-boundary validation: every malformed input is a 422, never a SQL cast. */
class SchedulingInputsTest {

    private static ApiException failure(Runnable command) {
        return assertThrows(ApiException.class, command::run);
    }

    @Test
    void acceptsAndRejectsOpaqueIdentifiers() {
        String id = UUID.randomUUID().toString();
        assertEquals(id, SchedulingInputs.uuid(id, "patientId"));
        assertEquals(422, failure(() -> SchedulingInputs.uuid(null, "patientId")).status());
        assertEquals(422, failure(() -> SchedulingInputs.uuid("not-a-uuid", "patientId")).status());
        assertEquals("INVALID_PATIENTID",
                failure(() -> SchedulingInputs.uuid("nope", "patientId")).code());
    }

    @Test
    void acceptsAndRejectsTenantLocalCodes() {
        assertEquals("MAIN", SchedulingInputs.code("MAIN", "facilityCode"));
        assertEquals("R51_A-9", SchedulingInputs.code("R51_A-9", "facilityCode"));
        assertEquals("A".repeat(32), SchedulingInputs.code("A".repeat(32), "facilityCode"));
        assertEquals(422, failure(() -> SchedulingInputs.code("main", "facilityCode")).status());
        assertEquals("INVALID_FACILITYCODE",
                failure(() -> SchedulingInputs.code("main", "facilityCode")).code());
        assertEquals(422, failure(() -> SchedulingInputs.code("M", "facilityCode")).status());
        assertEquals(422, failure(() -> SchedulingInputs.code(null, "facilityCode")).status());
        assertEquals(422, failure(() -> SchedulingInputs.code("A".repeat(33), "facilityCode")).status());
        assertEquals(422, failure(() -> SchedulingInputs.code("رمز", "facilityCode")).status());
    }

    @Test
    void parsesIsoInstants() {
        Instant instant = Instant.parse("2026-12-07T07:00:00Z");
        assertEquals(instant, SchedulingInputs.instant("2026-12-07T07:00:00Z"));
        assertEquals(422, failure(() -> SchedulingInputs.instant(null)).status());
        assertEquals(422, failure(() -> SchedulingInputs.instant("tomorrow")).status());
        assertEquals(422, failure(() -> SchedulingInputs.instant("2026-13-45T99:00:00Z")).status());
    }

    @Test
    void normalizesOptionalReasons() {
        assertNull(SchedulingInputs.reason(null));
        assertNull(SchedulingInputs.reason("   "));
        assertEquals("patient request", SchedulingInputs.reason("  patient request  "));
        assertEquals(422, failure(() -> SchedulingInputs.reason("x".repeat(201))).status());
    }

    @Test
    void validatesRequiredText() {
        assertEquals("Dr A", SchedulingInputs.text(" Dr A ", "displayName", 200));
        assertEquals(422, failure(() -> SchedulingInputs.text(null, "displayName", 200)).status());
        assertEquals(422, failure(() -> SchedulingInputs.text("  ", "displayName", 200)).status());
        assertEquals(422, failure(() -> SchedulingInputs.text("x".repeat(201), "displayName", 200))
                .status());
    }

    @Test
    void parsesIanaZones() {
        assertEquals(ZoneId.of("Asia/Riyadh"), SchedulingInputs.zone("Asia/Riyadh"));
        assertEquals(422, failure(() -> SchedulingInputs.zone("Mars/Olympus")).status());
    }

    @Test
    void parsesLocalWindowBoundaries() {
        assertEquals(LocalTime.of(9, 0), SchedulingInputs.localTime("09:00", "startLocal"));
        assertEquals(422, failure(() -> SchedulingInputs.localTime(null, "startLocal")).status());
        assertEquals(422, failure(() -> SchedulingInputs.localTime("nine", "startLocal")).status());
    }

    @Test
    void validatesWeekdaysAndDurations() {
        assertEquals(1, SchedulingInputs.weekday(1));
        assertEquals(7, SchedulingInputs.weekday(7));
        assertEquals(422, failure(() -> SchedulingInputs.weekday(null)).status());
        assertEquals(422, failure(() -> SchedulingInputs.weekday(0)).status());
        assertEquals(422, failure(() -> SchedulingInputs.weekday(8)).status());
        assertEquals(5, SchedulingInputs.duration(5));
        assertEquals(480, SchedulingInputs.duration(480));
        assertEquals(422, failure(() -> SchedulingInputs.duration(null)).status());
        assertEquals(422, failure(() -> SchedulingInputs.duration(4)).status());
        assertEquals(422, failure(() -> SchedulingInputs.duration(481)).status());
    }

    @Test
    void generatesOpaqueIdentifiers() {
        String id = SchedulingInputs.newId();
        assertEquals(id, UUID.fromString(id).toString());
        assertTrue(!id.equals(SchedulingInputs.newId()));
    }
}
