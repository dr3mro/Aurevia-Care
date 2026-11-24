package com.aurevia.it.schedulingfollowup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.scheduling.followup.BookingIntent;
import com.aurevia.scheduling.followup.CareTeamTask;
import com.aurevia.scheduling.followup.FollowUpAuth;
import com.aurevia.scheduling.followup.FollowUpBooking;
import com.aurevia.scheduling.followup.FollowUpConfig;
import com.aurevia.scheduling.followup.FollowUpOverride;
import com.aurevia.scheduling.followup.FollowUpRule;
import com.aurevia.scheduling.followup.FollowUpService;
import com.aurevia.scheduling.followup.FollowUpStore;
import com.aurevia.scheduling.followup.FollowUpSuggestion;
import com.aurevia.scheduling.followup.PriorFollowUp;
import com.aurevia.tenant.TenantContext;

/**
 * S-G1-06 acceptance (CLN-06): configured visit-type intervals, applicable prior
 * follow-up, rule explanation, authorized override provenance, explicit booking
 * confirmation and care-team tasks with owner, due date and outcome.
 *
 * <p>All fixtures are synthetic. Ports are fakes so the slice runs without a
 * database; tenant isolation is exercised with two organizations that share the
 * same local identifiers. Civil-date arithmetic is checked across a daylight
 * saving transition so a follow-up date is not silently shifted by an hour.</p>
 */
class FollowUpServiceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "clinician-a";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACILITY = "CLINIC_A";
    private static final String VISIT = "FOLLOWUP";
    private static final Instant ENCOUNTER = Instant.parse("2026-03-02T09:00:00Z");
    private static final LocalDate ENCOUNTER_DATE = LocalDate.of(2026, 3, 2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-02T10:00:00Z"), ZoneOffset.UTC);

    private FollowUpConfig config;
    private FollowUpAuth auth;
    private FollowUpBooking booking;
    private AuditAppender audit;
    private MemoryStore store;
    private FollowUpService service;

    @BeforeEach
    void setUp() {
        config = mock(FollowUpConfig.class);
        auth = mock(FollowUpAuth.class);
        booking = mock(FollowUpBooking.class);
        audit = mock(AuditAppender.class);
        store = new MemoryStore();
        service = new FollowUpService(config, store, auth, booking, audit, new ActionPolicy(), CLOCK);
        TenantContext.set(new TenantContext(ORG_A, SUBJECT, FollowUpService.PURPOSE, "corr-1"));
        when(config.facilityZone(FACILITY)).thenReturn(ZoneId.of("UTC"));
        when(booking.book(any())).thenReturn("booking-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(auth.activeRoles(SUBJECT)).thenReturn(List.of(roles));
    }

    /** A configured rule and no prior follow-up: the usual path. */
    private FollowUpSuggestion suggested() {
        authorized("reception");
        when(config.applicablePriorFollowUp(PATIENT, VISIT, ENCOUNTER)).thenReturn(Optional.empty());
        when(config.setting(FollowUpRule.configKey(VISIT))).thenReturn(Optional.of("14"));
        return service.suggest(PATIENT, FACILITY, VISIT, ENCOUNTER.toString());
    }

    private static ApiException failure(int status, Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    private static String randomId() {
        return UUID.randomUUID().toString();
    }

    // ---- FollowUpRule parsing -------------------------------------------------

    @Test
    void configKeyNormalizesVisitType() {
        assertEquals("followup.interval.followup", FollowUpRule.configKey(VISIT));
    }

    @Test
    void ruleParsesApprovedInterval() {
        FollowUpRule rule = FollowUpRule.from(VISIT, "14");
        assertEquals(14, rule.intervalDays());
        assertEquals(FollowUpRule.configKey(VISIT), rule.configKey());
        assertEquals(VISIT, rule.visitType());
    }

    @Test
    void ruleRejectsMissingInvalidAndOutOfRangeValues() {
        assertNull(FollowUpRule.from(VISIT, null));
        assertNull(FollowUpRule.from(VISIT, "  "));
        assertNull(FollowUpRule.from(VISIT, "two weeks"));
        assertNull(FollowUpRule.from(VISIT, "0"));
        assertNull(FollowUpRule.from(VISIT, "-1"));
        assertNull(FollowUpRule.from(VISIT, "3651"));
    }

    // ---- suggest --------------------------------------------------------------

    @Test
    void suggestUsesConfiguredIntervalAndExplainsRule() {
        FollowUpSuggestion suggestion = suggested();
        assertEquals(ENCOUNTER_DATE.plusDays(14), suggestion.suggestedDate());
        assertEquals(Integer.valueOf(14), suggestion.intervalDays());
        assertEquals("CONFIGURED_INTERVAL", suggestion.basis());
        assertTrue(suggestion.ruleExplanation().contains("14 days"));
        assertTrue(suggestion.ruleExplanation().contains(FollowUpRule.configKey(VISIT)));
        assertTrue(suggestion.ruleExplanation().contains(ENCOUNTER_DATE.toString()));
        assertEquals("UTC", suggestion.timeZone());
        assertFalse(suggestion.confirmed());
        assertNull(suggestion.bookingId());
        assertEquals(1, suggestion.version());
        verify(booking, never()).book(any());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_SUGGEST"),
                anyString(), anyString(), eq(suggestion.id()), eq("SUCCESS"));
    }

    @Test
    void suggestUsesCivilDateAcrossDaylightSavingTransition() {
        authorized("reception");
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant springForward = Instant.parse("2026-03-06T15:00:00Z");
        when(config.facilityZone(FACILITY)).thenReturn(newYork);
        when(config.applicablePriorFollowUp(PATIENT, VISIT, springForward)).thenReturn(Optional.empty());
        when(config.setting(FollowUpRule.configKey(VISIT))).thenReturn(Optional.of("7"));
        FollowUpSuggestion suggestion = service.suggest(PATIENT, FACILITY, VISIT,
                springForward.toString());
        assertEquals(LocalDate.of(2026, 3, 13), suggestion.suggestedDate());
        assertEquals("America/New_York", suggestion.timeZone());
    }

    @Test
    void suggestUsesPriorFollowUpAndExplainsPrecedenceWithConfiguredRule() {
        authorized("reception");
        PriorFollowUp prior = new PriorFollowUp("appointment-7", ENCOUNTER_DATE.plusDays(20));
        when(config.applicablePriorFollowUp(PATIENT, VISIT, ENCOUNTER)).thenReturn(Optional.of(prior));
        when(config.setting(FollowUpRule.configKey(VISIT))).thenReturn(Optional.of("14"));
        FollowUpSuggestion suggestion = service.suggest(PATIENT, FACILITY, VISIT, ENCOUNTER.toString());
        assertEquals(prior.date(), suggestion.suggestedDate());
        assertNull(suggestion.intervalDays());
        assertEquals("PRIOR_FOLLOW_UP", suggestion.basis());
        assertTrue(suggestion.ruleExplanation().contains("appointment-7"));
        assertTrue(suggestion.ruleExplanation().contains("takes precedence"));
        assertTrue(suggestion.ruleExplanation().contains("14 days"));
    }

    @Test
    void suggestUsesPriorFollowUpWhenNoConfiguredRuleExists() {
        authorized("reception");
        PriorFollowUp prior = new PriorFollowUp("appointment-8", ENCOUNTER_DATE.plusDays(21));
        when(config.applicablePriorFollowUp(PATIENT, VISIT, ENCOUNTER)).thenReturn(Optional.of(prior));
        when(config.setting(FollowUpRule.configKey(VISIT))).thenReturn(Optional.empty());
        FollowUpSuggestion suggestion = service.suggest(PATIENT, FACILITY, VISIT, ENCOUNTER.toString());
        assertEquals(prior.date(), suggestion.suggestedDate());
        assertEquals("PRIOR_FOLLOW_UP", suggestion.basis());
        assertTrue(suggestion.ruleExplanation().contains("no configured interval"));
    }

    @Test
    void suggestFailsWhenNeitherRuleNorPriorExists() {
        authorized("reception");
        when(config.applicablePriorFollowUp(PATIENT, VISIT, ENCOUNTER)).thenReturn(Optional.empty());
        when(config.setting(FollowUpRule.configKey(VISIT))).thenReturn(Optional.empty());
        ApiException error = failure(422, () -> service.suggest(PATIENT, FACILITY, VISIT, ENCOUNTER.toString()));
        assertEquals("NO_FOLLOW_UP_RULE", error.code());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_SUGGEST"),
                anyString(), anyString(), eq(PATIENT), eq("NOT_FOUND"));
        verify(booking, never()).book(any());
    }

    @Test
    void suggestRejectsInvalidInputs() {
        assertEquals("INVALID_PATIENT_ID", failure(422, () -> service.suggest(null, FACILITY, VISIT,
                ENCOUNTER.toString())).code());
        assertEquals("INVALID_PATIENT_ID", failure(422, () -> service.suggest("not-a-uuid", FACILITY,
                VISIT, ENCOUNTER.toString())).code());
        assertEquals("INVALID_FACILITY_CODE", failure(422, () -> service.suggest(PATIENT, null,
                VISIT, ENCOUNTER.toString())).code());
        assertEquals("INVALID_FACILITY_CODE", failure(422, () -> service.suggest(PATIENT, "clinic_a",
                VISIT, ENCOUNTER.toString())).code());
        assertEquals("INVALID_FACILITY_CODE", failure(422, () -> service.suggest(PATIENT, "C",
                VISIT, ENCOUNTER.toString())).code());
        assertEquals("INVALID_FACILITY_CODE", failure(422, () -> service.suggest(PATIENT, "رمز",
                VISIT, ENCOUNTER.toString())).code());
        assertEquals("INVALID_FACILITY_CODE", failure(422, () -> service.suggest(PATIENT, "A".repeat(33),
                VISIT, ENCOUNTER.toString())).code());
        assertEquals("INVALID_VISIT_TYPE", failure(422, () -> service.suggest(PATIENT, FACILITY,
                null, ENCOUNTER.toString())).code());
        assertEquals("INVALID_VISIT_TYPE", failure(422, () -> service.suggest(PATIENT, FACILITY,
                "followup", ENCOUNTER.toString())).code());
        assertEquals("INVALID_ENCOUNTER_AT", failure(422, () -> service.suggest(PATIENT, FACILITY,
                VISIT, null)).code());
        assertEquals("INVALID_ENCOUNTER_AT", failure(422, () -> service.suggest(PATIENT, FACILITY,
                VISIT, "yesterday")).code());
        verify(booking, never()).book(any());
    }

    @Test
    void suggestAcceptsMaxLengthFacilityCode() {
        String maxFacility = "A".repeat(32);
        authorized("reception");
        when(config.facilityZone(maxFacility)).thenReturn(ZoneId.of("UTC"));
        when(config.applicablePriorFollowUp(PATIENT, VISIT, ENCOUNTER)).thenReturn(Optional.empty());
        when(config.setting(FollowUpRule.configKey(VISIT))).thenReturn(Optional.empty());
        ApiException error = failure(422, () -> service.suggest(PATIENT, maxFacility, VISIT,
                ENCOUNTER.toString()));
        assertEquals("NO_FOLLOW_UP_RULE", error.code());
    }

    @Test
    void suggestDeniesInactiveMembershipAndAudits() {
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, failure(403, () -> service.suggest(PATIENT, FACILITY, VISIT,
                ENCOUNTER.toString())).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_SUGGEST"),
                anyString(), anyString(), eq(PATIENT), eq("DENIED"));
    }

    @Test
    void suggestDeniesMissingPermissionAndAudits() {
        authorized("privacy_auditor");
        assertEquals(403, failure(403, () -> service.suggest(PATIENT, FACILITY, VISIT,
                ENCOUNTER.toString())).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_SUGGEST"),
                anyString(), anyString(), eq(PATIENT), eq("DENIED"));
    }

    // ---- override -------------------------------------------------------------

    @Test
    void overrideRecordsActorReasonTimeAndOriginalDate() {
        FollowUpSuggestion original = suggested();
        LocalDate replacement = ENCOUNTER_DATE.plusDays(30);
        FollowUpSuggestion updated = service.override(original.id(), replacement.toString(),
                "Clinician directed a later review");
        FollowUpOverride provenance = updated.override();
        assertEquals(SUBJECT, provenance.actor());
        assertEquals("Clinician directed a later review", provenance.reason());
        assertEquals(CLOCK.instant(), provenance.recordedAt());
        assertEquals(original.suggestedDate(), provenance.originalDate());
        assertEquals(replacement, provenance.replacementDate());
        assertEquals(replacement, updated.suggestedDate());
        assertEquals(2, updated.version());
        assertTrue(updated.ruleExplanation().contains(SUBJECT));
        assertTrue(updated.ruleExplanation().contains("Clinician directed a later review"));
        assertTrue(updated.ruleExplanation().contains(original.suggestedDate().toString()));
        assertFalse(updated.confirmed());
        verify(booking, never()).book(any());
    }

    @Test
    void overrideRejectsInvalidInputs() {
        assertEquals("INVALID_SUGGESTION_ID", failure(422,
                () -> service.override("bad", ENCOUNTER_DATE.toString(), "reason")).code());
        assertEquals("INVALID_REQUESTED_DATE", failure(422,
                () -> service.override(randomId(), null, "reason")).code());
        assertEquals("INVALID_REQUESTED_DATE", failure(422,
                () -> service.override(randomId(), "nope", "reason")).code());
        assertEquals("INVALID_REASON", failure(422,
                () -> service.override(randomId(), ENCOUNTER_DATE.toString(), null)).code());
        assertEquals("INVALID_REASON", failure(422,
                () -> service.override(randomId(), ENCOUNTER_DATE.toString(), "  ")).code());
        assertEquals("INVALID_REASON", failure(422,
                () -> service.override(randomId(), ENCOUNTER_DATE.toString(), "x".repeat(201))).code());
    }

    @Test
    void overrideDeniesInactiveMembershipAndAudits() {
        FollowUpSuggestion original = suggested();
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, failure(403, () -> service.override(original.id(),
                ENCOUNTER_DATE.toString(), "reason")).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_OVERRIDE"),
                anyString(), anyString(), eq(original.id()), eq("DENIED"));
    }

    @Test
    void overrideDeniesMissingPermissionAndAudits() {
        FollowUpSuggestion original = suggested();
        authorized("clinician");
        assertEquals(403, failure(403, () -> service.override(original.id(),
                ENCOUNTER_DATE.toString(), "reason")).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_OVERRIDE"),
                anyString(), anyString(), eq(original.id()), eq("DENIED"));
    }

    @Test
    void overrideConcealsUnknownSuggestion() {
        authorized("tenant_admin");
        String id = randomId();
        assertEquals("NOT_FOUND", failure(404, () -> service.override(id,
                ENCOUNTER_DATE.toString(), "reason")).code());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_OVERRIDE"),
                anyString(), anyString(), eq(id), eq("NOT_FOUND"));
    }

    @Test
    void overrideRejectsConfirmedSuggestion() {
        FollowUpSuggestion original = suggested();
        authorized("tenant_admin");
        service.confirm(original.id(), original.version());
        assertEquals(409, failure(409, () -> service.override(original.id(),
                ENCOUNTER_DATE.toString(), "reason")).status());
    }

    @Test
    void overrideRejectsSecondOverride() {
        FollowUpSuggestion original = suggested();
        service.override(original.id(), ENCOUNTER_DATE.toString(), "first");
        assertEquals(409, failure(409, () -> service.override(original.id(),
                ENCOUNTER_DATE.toString(), "second")).status());
    }

    // ---- confirm --------------------------------------------------------------

    @Test
    void confirmCreatesExactlyOneBookingWithFinalDate() {
        FollowUpSuggestion original = suggested();
        LocalDate replacement = ENCOUNTER_DATE.plusDays(30);
        FollowUpSuggestion overridden = service.override(original.id(), replacement.toString(),
                "reason");
        FollowUpSuggestion confirmed = service.confirm(original.id(), overridden.version());
        assertTrue(confirmed.confirmed());
        assertEquals("booking-1", confirmed.bookingId());
        assertEquals(3, confirmed.version());
        verify(booking, times(1)).book(new BookingIntent(original.id(), ORG_A, PATIENT, FACILITY,
                VISIT, replacement, "UTC"));
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_CONFIRM"),
                anyString(), anyString(), eq(original.id()), eq("SUCCESS"));
    }

    @Test
    void confirmRejectsInvalidInputs() {
        assertEquals("INVALID_SUGGESTION_ID", failure(422, () -> service.confirm("bad", 1)).code());
        assertEquals("INVALID_VERSION", failure(422, () -> service.confirm(randomId(), 0)).code());
    }

    @Test
    void confirmDeniesInactiveMembershipAndAudits() {
        FollowUpSuggestion original = suggested();
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, failure(403, () -> service.confirm(original.id(), 1)).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_CONFIRM"),
                anyString(), anyString(), eq(original.id()), eq("DENIED"));
    }

    @Test
    void confirmDeniesMissingPermissionAndAudits() {
        FollowUpSuggestion original = suggested();
        authorized("clinician");
        assertEquals(403, failure(403, () -> service.confirm(original.id(), 1)).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_CONFIRM"),
                anyString(), anyString(), eq(original.id()), eq("DENIED"));
    }

    @Test
    void confirmConcealsUnknownSuggestion() {
        authorized("reception");
        String id = randomId();
        assertEquals("NOT_FOUND", failure(404, () -> service.confirm(id, 1)).code());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_CONFIRM"),
                anyString(), anyString(), eq(id), eq("NOT_FOUND"));
    }

    @Test
    void confirmRejectsStaleVersion() {
        FollowUpSuggestion original = suggested();
        assertEquals(412, failure(412, () -> service.confirm(original.id(), 2)).status());
        verify(booking, never()).book(any());
    }

    @Test
    void confirmRejectsSecondConfirmation() {
        FollowUpSuggestion original = suggested();
        service.confirm(original.id(), 1);
        assertEquals("ALREADY_CONFIRMED", failure(409, () -> service.confirm(original.id(), 2)).code());
        verify(booking, times(1)).book(any());
    }

    // ---- care-team tasks -------------------------------------------------------

    @Test
    void createTaskCapturesOwnerDueDateAndOutcomeIsInitiallyAbsent() {
        FollowUpSuggestion original = suggested();
        CareTeamTask task = service.createTask(original.id(), "nurse-1",
                ENCOUNTER_DATE.plusDays(12).toString(), "Call the patient before the visit");
        assertEquals(original.id(), task.followUpId());
        assertEquals("nurse-1", task.owner());
        assertEquals(ENCOUNTER_DATE.plusDays(12), task.dueDate());
        assertEquals("Call the patient before the visit", task.instruction());
        assertEquals(CareTeamTask.OPEN, task.status());
        assertNull(task.outcome());
        assertEquals(SUBJECT, task.createdBy());
        assertEquals(CLOCK.instant(), task.createdAt());
        assertEquals(ORG_A, task.organizationId());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_CREATE"),
                anyString(), anyString(), eq(task.id()), eq("SUCCESS"));
    }

    @Test
    void createTaskRejectsInvalidInputs() {
        assertEquals("INVALID_FOLLOW_UP_ID", failure(422,
                () -> service.createTask("bad", "nurse", ENCOUNTER_DATE.toString(), "do it")).code());
        assertEquals("INVALID_OWNER", failure(422,
                () -> service.createTask(randomId(), null, ENCOUNTER_DATE.toString(), "do it")).code());
        assertEquals("INVALID_OWNER", failure(422,
                () -> service.createTask(randomId(), " ", ENCOUNTER_DATE.toString(), "do it")).code());
        assertEquals("INVALID_OWNER", failure(422,
                () -> service.createTask(randomId(), "x".repeat(65), ENCOUNTER_DATE.toString(),
                        "do it")).code());
        assertEquals("INVALID_DUE_DATE", failure(422,
                () -> service.createTask(randomId(), "nurse", null, "do it")).code());
        assertEquals("INVALID_DUE_DATE", failure(422,
                () -> service.createTask(randomId(), "nurse", "later", "do it")).code());
        assertEquals("INVALID_INSTRUCTION", failure(422,
                () -> service.createTask(randomId(), "nurse", ENCOUNTER_DATE.toString(), "  ")).code());
        assertEquals("INVALID_INSTRUCTION", failure(422,
                () -> service.createTask(randomId(), "nurse", ENCOUNTER_DATE.toString(),
                        "x".repeat(501))).code());
    }

    @Test
    void createTaskDeniesInactiveMembershipAndAudits() {
        FollowUpSuggestion original = suggested();
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, failure(403, () -> service.createTask(original.id(), "nurse",
                ENCOUNTER_DATE.toString(), "do it")).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_CREATE"),
                anyString(), anyString(), eq(original.id()), eq("DENIED"));
    }

    @Test
    void createTaskDeniesMissingPermissionAndAudits() {
        FollowUpSuggestion original = suggested();
        authorized("privacy_auditor");
        assertEquals(403, failure(403, () -> service.createTask(original.id(), "nurse",
                ENCOUNTER_DATE.toString(), "do it")).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_CREATE"),
                anyString(), anyString(), eq(original.id()), eq("DENIED"));
    }

    @Test
    void createTaskConcealsUnknownFollowUp() {
        authorized("reception");
        String id = randomId();
        assertEquals("NOT_FOUND", failure(404, () -> service.createTask(id, "nurse",
                ENCOUNTER_DATE.toString(), "do it")).code());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_CREATE"),
                anyString(), anyString(), eq(id), eq("NOT_FOUND"));
    }

    @Test
    void completeTaskRecordsOutcomeAndRejectsSecondCompletion() {
        FollowUpSuggestion original = suggested();
        CareTeamTask task = service.createTask(original.id(), "nurse", ENCOUNTER_DATE.toString(),
                "do it");
        authorized("clinician");
        CareTeamTask completed = service.completeTask(task.id(), "Patient confirmed by phone");
        assertEquals(CareTeamTask.COMPLETED, completed.status());
        assertEquals("Patient confirmed by phone", completed.outcome());
        assertEquals("ALREADY_COMPLETED", failure(409,
                () -> service.completeTask(task.id(), "again")).code());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_COMPLETE"),
                anyString(), anyString(), eq(task.id()), eq("SUCCESS"));
    }

    @Test
    void completeTaskRejectsInvalidInputs() {
        assertEquals("INVALID_TASK_ID", failure(422,
                () -> service.completeTask("bad", "done")).code());
        assertEquals("INVALID_OUTCOME", failure(422,
                () -> service.completeTask(randomId(), null)).code());
        assertEquals("INVALID_OUTCOME", failure(422,
                () -> service.completeTask(randomId(), " ")).code());
        assertEquals("INVALID_OUTCOME", failure(422,
                () -> service.completeTask(randomId(), "x".repeat(201))).code());
    }

    @Test
    void completeTaskDeniesInactiveMembershipAndAudits() {
        FollowUpSuggestion original = suggested();
        CareTeamTask task = service.createTask(original.id(), "nurse", ENCOUNTER_DATE.toString(),
                "do it");
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, failure(403, () -> service.completeTask(task.id(), "done")).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_COMPLETE"),
                anyString(), anyString(), eq(task.id()), eq("DENIED"));
    }

    @Test
    void completeTaskDeniesMissingPermissionAndAudits() {
        FollowUpSuggestion original = suggested();
        CareTeamTask task = service.createTask(original.id(), "nurse", ENCOUNTER_DATE.toString(),
                "do it");
        authorized("reception");
        assertEquals(403, failure(403, () -> service.completeTask(task.id(), "done")).status());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_COMPLETE"),
                anyString(), anyString(), eq(task.id()), eq("DENIED"));
    }

    @Test
    void completeTaskConcealsUnknownTask() {
        authorized("clinician");
        String id = randomId();
        assertEquals("NOT_FOUND", failure(404, () -> service.completeTask(id, "done")).code());
        verify(audit).append(eq(TenantContext.current()), anyString(), eq("FOLLOWUP_TASK_COMPLETE"),
                anyString(), anyString(), eq(id), eq("NOT_FOUND"));
    }

    @Test
    void tasksListsOnlyTheActiveTenantsTasks() {
        FollowUpSuggestion original = suggested();
        CareTeamTask own = service.createTask(original.id(), "nurse-a", ENCOUNTER_DATE.toString(),
                "local follow-up");
        // The peer tenant uses the same patient, facility and visit-type codes but a
        // different approved interval; its suggestion and task are server-owned locally.
        TenantContext.set(new TenantContext(ORG_B, SUBJECT, FollowUpService.PURPOSE, "corr-2"));
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(auth.activeRoles(SUBJECT)).thenReturn(List.of("reception"));
        when(config.setting(FollowUpRule.configKey(VISIT))).thenReturn(Optional.of("30"));
        FollowUpSuggestion peer = service.suggest(PATIENT, FACILITY, VISIT, ENCOUNTER.toString());
        CareTeamTask peerTask = service.createTask(peer.id(), "nurse-b", ENCOUNTER_DATE.toString(),
                "peer follow-up");
        assertNotEquals(original.id(), peer.id());
        TenantContext.set(new TenantContext(ORG_A, SUBJECT, FollowUpService.PURPOSE, "corr-1"));

        List<CareTeamTask> tasks = service.tasks(original.id());
        assertEquals(1, tasks.size());
        assertEquals(own.id(), tasks.get(0).id());
        assertEquals(ORG_A, tasks.get(0).organizationId());
        assertNotEquals(peerTask.id(), tasks.get(0).id());
        assertNotEquals("nurse-b", tasks.get(0).owner());
    }

    @Test
    void tasksRejectsInvalidInputDenialAndConcealment() {
        assertEquals("INVALID_FOLLOW_UP_ID", failure(422, () -> service.tasks("bad")).code());
        when(auth.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, failure(403, () -> service.tasks(randomId())).status());
        authorized("tenant_admin");
        String id = randomId();
        assertEquals("NOT_FOUND", failure(404, () -> service.tasks(id)).code());
    }

    // ---- tenant isolation ------------------------------------------------------

    @Test
    void peerTenantCannotReadOverrideOrConfirmAnotherTenantsSuggestion() {
        FollowUpSuggestion original = suggested();
        TenantContext.set(new TenantContext(ORG_B, SUBJECT, FollowUpService.PURPOSE, "corr-2"));
        authorized("tenant_admin");
        assertEquals("NOT_FOUND", failure(404, () -> service.override(original.id(),
                ENCOUNTER_DATE.toString(), "peer override")).code());
        assertEquals("NOT_FOUND", failure(404, () -> service.confirm(original.id(), 1)).code());
        assertEquals("NOT_FOUND", failure(404, () -> service.createTask(original.id(), "nurse",
                ENCOUNTER_DATE.toString(), "peer task")).code());
        assertEquals("NOT_FOUND", failure(404, () -> service.tasks(original.id())).code());
        verify(booking, never()).book(any());
    }

    @Test
    void commandsFailClosedWithoutTenantContext() {
        TenantContext.clear();
        assertEquals(401, failure(401, () -> service.suggest(PATIENT, FACILITY, VISIT,
                ENCOUNTER.toString())).status());
        assertEquals(401, failure(401, () -> service.override(randomId(),
                ENCOUNTER_DATE.toString(), "reason")).status());
        assertEquals(401, failure(401, () -> service.confirm(randomId(), 1)).status());
        assertEquals(401, failure(401, () -> service.createTask(randomId(), "nurse",
                ENCOUNTER_DATE.toString(), "do it")).status());
        assertEquals(401, failure(401, () -> service.completeTask(randomId(), "done")).status());
        assertEquals(401, failure(401, () -> service.tasks(randomId())).status());
        verify(booking, never()).book(any());
    }

    @Test
    void bookingFailureDoesNotStoreAConfirmedSuggestion() {
        FollowUpSuggestion original = suggested();
        when(booking.book(any())).thenThrow(new IllegalStateException("partner outage"));
        assertThrows(IllegalStateException.class, () -> service.confirm(original.id(), 1));
        assertEquals(1, store.findSuggestion(ORG_A, original.id()).orElseThrow().version());
        assertFalse(store.findSuggestion(ORG_A, original.id()).orElseThrow().confirmed());
        verify(audit, never()).append(eq(TenantContext.current()), anyString(),
                eq("FOLLOWUP_CONFIRM"), anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void peerTenantCannotCompleteAnotherTenantsTask() {
        FollowUpSuggestion original = suggested();
        CareTeamTask task = service.createTask(original.id(), "nurse", ENCOUNTER_DATE.toString(),
                "do it");
        TenantContext.set(new TenantContext(ORG_B, SUBJECT, FollowUpService.PURPOSE, "corr-2"));
        authorized("clinician");
        assertEquals("NOT_FOUND", failure(404, () -> service.completeTask(task.id(), "done")).code());
    }

    /** In-memory tenant-scoped store used as a fake for the persistence port. */
    private static final class MemoryStore implements FollowUpStore {
        private final Map<String, FollowUpSuggestion> suggestions = new HashMap<>();
        private final Map<String, CareTeamTask> tasks = new HashMap<>();

        private static String key(String organizationId, String id) {
            return organizationId + "/" + id;
        }

        @Override
        public void saveSuggestion(FollowUpSuggestion suggestion) {
            suggestions.put(key(suggestion.organizationId(), suggestion.id()), suggestion);
        }

        @Override
        public Optional<FollowUpSuggestion> findSuggestion(String organizationId, String id) {
            return Optional.ofNullable(suggestions.get(key(organizationId, id)));
        }

        @Override
        public void saveTask(CareTeamTask task) {
            tasks.put(key(task.organizationId(), task.id()), task);
        }

        @Override
        public Optional<CareTeamTask> findTask(String organizationId, String id) {
            return Optional.ofNullable(tasks.get(key(organizationId, id)));
        }

        @Override
        public List<CareTeamTask> tasksFor(String organizationId, String followUpId) {
            List<CareTeamTask> result = new ArrayList<>();
            for (CareTeamTask task : tasks.values()) {
                if (organizationId.equals(task.organizationId())
                        && followUpId.equals(task.followUpId())) {
                    result.add(task);
                }
            }
            return result;
        }
    }
}
