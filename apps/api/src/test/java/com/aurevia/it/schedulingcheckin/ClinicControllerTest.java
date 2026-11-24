package com.aurevia.it.schedulingcheckin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.scheduling.checkin.CheckInService;
import com.aurevia.scheduling.checkin.ClinicController;
import com.aurevia.scheduling.checkin.CheckInCommands.Assign;
import com.aurevia.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.scheduling.checkin.CheckInCommands.Intake;
import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;
import com.aurevia.scheduling.checkin.Verification;
import com.aurevia.scheduling.checkin.WorklistItem;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentState;

/** S-G1-02 HTTP adapter: each route parses its precondition and delegates unchanged. */
class ClinicControllerTest {

    private static final String APPOINTMENT = "22222222-2222-2222-2222-222222222222";
    private static final String ITEM = "44444444-4444-4444-4444-444444444444";

    private CheckInService service;
    private ClinicController controller;

    @BeforeEach
    void setUp() {
        service = mock(CheckInService.class);
        controller = new ClinicController(service);
    }

    private static WorklistItem item() {
        return new WorklistItem(ITEM, APPOINTMENT, null, "patient", "MAIN",
                Instant.parse("2026-12-07T07:00:00Z"), 0, null, null, 1);
    }

    @Test
    void verifyReturnsCreated() {
        VerifyIdentity request = new VerifyIdentity(APPOINTMENT, List.of("MRN", "DOB"),
                "two_identifiers", null, null);
        Verification verification = new Verification("id", APPOINTMENT, "patient", "MAIN",
                "two_identifiers", List.of("MRN", "DOB"), null, null, "reception-a",
                Instant.parse("2026-11-19T10:00:00Z"));
        when(service.verify(request)).thenReturn(verification);
        assertEquals(201, controller.verify(request).getStatusCode().value());
        assertEquals(verification, controller.verify(request).getBody());
        verify(service, org.mockito.Mockito.times(2)).verify(request);
    }

    @Test
    void checkInParsesIfMatch() {
        when(service.checkIn(APPOINTMENT, 1)).thenReturn(item());
        assertEquals(ITEM, controller.checkIn(APPOINTMENT, "1").id());
        assertEquals(ITEM, controller.checkIn(APPOINTMENT, "W/\"1\"").id());
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.checkIn(APPOINTMENT, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> controller.checkIn(APPOINTMENT, "abc")).status());
    }

    @Test
    void openEncounterParsesIfMatch() {
        Appointment opened = new Appointment(APPOINTMENT, 2, "patient", "MAIN", "DR-A", null,
                "NEW", Instant.parse("2026-12-07T07:00:00Z"), null, AppointmentState.IN_PROGRESS,
                null, null, null);
        when(service.openEncounter(APPOINTMENT, 2)).thenReturn(opened);
        assertEquals(AppointmentState.IN_PROGRESS, controller.openEncounter(APPOINTMENT, "2").state());
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.openEncounter(APPOINTMENT, null)).status());
    }

    @Test
    void worklistDelegatesFacilityAndLimit() {
        when(service.worklist("MAIN", 5)).thenReturn(List.of(item()));
        assertEquals(1, controller.worklist("MAIN", 5).size());
        verify(service).worklist("MAIN", 5);
    }

    @Test
    void assignAndHandoffParseIfMatch() {
        Assign assign = new Assign("nurse-a");
        when(service.assign(ITEM, assign, 1)).thenReturn(item());
        assertEquals(ITEM, controller.assign(ITEM, assign, "1").id());
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.assign(ITEM, assign, null)).status());

        Handoff handoff = new Handoff("nurse-b", "coverage");
        when(service.handoff(ITEM, handoff, 1)).thenReturn(item());
        assertEquals(ITEM, controller.handoff(ITEM, handoff, "1").id());
        assertEquals(422, assertThrows(ApiException.class,
                () -> controller.handoff(ITEM, handoff, "x")).status());
    }

    @Test
    void intakeReturnsCreated() {
        Intake request = new Intake("MAIN", "patient", "walk-in");
        when(service.linkIntake(request)).thenReturn(item());
        assertEquals(201, controller.intake(request).getStatusCode().value());
        verify(service).linkIntake(any());
    }
}
