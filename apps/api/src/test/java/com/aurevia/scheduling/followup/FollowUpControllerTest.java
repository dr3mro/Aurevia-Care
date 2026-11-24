package com.aurevia.scheduling.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** CLN-06 HTTP binding tier: the controller only delegates and parses If-Match. */
class FollowUpControllerTest {

    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TASK = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private FollowUpCommandService commands;
    private FollowUpController controller;
    private FollowUpSuggestion suggestion;
    private CareTeamTask task;

    @BeforeEach
    void setUp() {
        commands = mock(FollowUpCommandService.class);
        controller = new FollowUpController(commands);
        suggestion = new FollowUpSuggestion(ID, "org", "patient", "CLINIC", "FOLLOW",
                java.time.Instant.parse("2026-03-06T15:00:00Z"), "America/New_York",
                LocalDate.of(2026, 3, 20), 14, "CONFIGURED_INTERVAL", "rule", null, false, null, 1);
        task = new CareTeamTask(TASK, "org", ID, "nurse", LocalDate.of(2026, 3, 18),
                "call", CareTeamTask.OPEN, null, "clinician", java.time.Instant.parse("2026-03-06T16:00:00Z"));
    }

    @Test
    void suggestDelegatesEveryField() {
        FollowUpController.SuggestRequest request = new FollowUpController.SuggestRequest(
                "patient", "CLINIC", "FOLLOW", "2026-03-06T15:00:00Z");
        when(commands.suggest("patient", "CLINIC", "FOLLOW", "2026-03-06T15:00:00Z"))
                .thenReturn(suggestion);
        assertEquals(suggestion, controller.suggest(request));
        verify(commands).suggest("patient", "CLINIC", "FOLLOW", "2026-03-06T15:00:00Z");
    }

    @Test
    void overrideDelegatesIdDateAndReason() {
        FollowUpController.OverrideRequest request =
                new FollowUpController.OverrideRequest("2026-03-25", "clinician directed");
        when(commands.override(ID, "2026-03-25", "clinician directed")).thenReturn(suggestion);
        assertEquals(suggestion, controller.override(ID, request));
        verify(commands).override(ID, "2026-03-25", "clinician directed");
    }

    @Test
    void confirmParsesIfMatchAndDelegates() {
        when(commands.confirm(ID, 3)).thenReturn(suggestion);
        assertEquals(suggestion, controller.confirm(ID, "W/\"3\""));
        verify(commands).confirm(ID, 3);
    }

    @Test
    void confirmRequiresAValidPrecondition() {
        assertEquals(428, assertThrows(ApiException.class, () -> controller.confirm(ID, null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> controller.confirm(ID, "abc")).status());
    }

    @Test
    void taskRoutesDelegateExactly() {
        FollowUpController.TaskRequest create =
                new FollowUpController.TaskRequest("nurse", "2026-03-18", "call");
        when(commands.createTask(ID, "nurse", "2026-03-18", "call")).thenReturn(task);
        when(commands.completeTask(TASK, "done")).thenReturn(task.complete("done"));
        when(commands.tasks(ID)).thenReturn(List.of(task));
        assertEquals(task, controller.createTask(ID, create));
        assertEquals(CareTeamTask.COMPLETED, controller.completeTask(TASK,
                new FollowUpController.CompleteRequest("done")).status());
        assertEquals(1, controller.tasks(ID).size());
        verify(commands).createTask(ID, "nurse", "2026-03-18", "call");
        verify(commands).completeTask(TASK, "done");
        verify(commands).tasks(ID);
    }

    @Test
    void requestRecordsExposeTheirParts() {
        assertEquals("patient", new FollowUpController.SuggestRequest("patient", "CLINIC", "FOLLOW", "at")
                .patientId());
        assertEquals("CLINIC", new FollowUpController.SuggestRequest("patient", "CLINIC", "FOLLOW", "at")
                .facilityCode());
        assertEquals("FOLLOW", new FollowUpController.SuggestRequest("patient", "CLINIC", "FOLLOW", "at")
                .visitType());
        assertEquals("at", new FollowUpController.SuggestRequest("patient", "CLINIC", "FOLLOW", "at")
                .encounterAt());
        assertEquals("2026-03-25", new FollowUpController.OverrideRequest("2026-03-25", "why")
                .requestedDate());
        assertEquals("why", new FollowUpController.OverrideRequest("2026-03-25", "why").reason());
        assertEquals("nurse", new FollowUpController.TaskRequest("nurse", "date", "call").owner());
        assertEquals("date", new FollowUpController.TaskRequest("nurse", "date", "call").dueDate());
        assertEquals("call", new FollowUpController.TaskRequest("nurse", "date", "call").instruction());
        assertEquals("done", new FollowUpController.CompleteRequest("done").outcome());
    }
}
