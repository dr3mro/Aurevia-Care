package com.aurevia.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.messaging.service.ServiceProfileContract.WorkState;

/**
 * Unit acceptance for the pure MSG-06 domain contract, input validation and
 * bounded action matrix. Every fixture below is synthetic; no patient data is
 * used and no clock or generator is nondeterministic.
 */
class ServiceProfileDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void profileStatusRoundTripsItsWireKeyAndRejectsUnknown() {
        assertEquals(ProfileStatus.DRAFT, ProfileStatus.from("draft"));
        assertEquals(ProfileStatus.APPROVED, ProfileStatus.from("approved"));
        assertEquals("approved", ProfileStatus.APPROVED.wire());
        assertThrows(IllegalArgumentException.class, () -> ProfileStatus.from("unknown"));
    }

    @Test
    void workStateRoundTripsEveryWireKeyAndRejectsUnknown() {
        assertEquals(WorkState.AWAITING_STAFF, WorkState.from("awaiting_staff"));
        assertEquals(WorkState.AWAITING_PATIENT, WorkState.from("awaiting_patient"));
        assertEquals(WorkState.RESOLVED, WorkState.from("resolved"));
        assertEquals(WorkState.CLOSED, WorkState.from("closed"));
        assertEquals("closed", WorkState.CLOSED.wire());
        assertThrows(IllegalArgumentException.class, () -> WorkState.from("unknown"));
    }

    @Test
    void serviceWindowReportsWhetherItWrapsIntoTheNextDay() {
        ServiceWindow sameDay = new ServiceWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
        ServiceWindow wraps = new ServiceWindow(DayOfWeek.FRIDAY, LocalTime.of(22, 0), LocalTime.of(2, 0));
        assertFalse(sameDay.wraps());
        assertTrue(wraps.wraps());
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        List<ServiceWindow> source = new java.util.ArrayList<>(List.of(
                new ServiceWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        ServiceHours hours = new ServiceHours(ZoneId.of("UTC"), source);
        source.clear();
        assertEquals(1, hours.windows().size());
        assertThrows(UnsupportedOperationException.class, () -> hours.windows().clear());

        ServiceProfile profile = profile(List.of("collection"), List.of(new LocalizedText("en", "call us")));
        assertThrows(UnsupportedOperationException.class, () -> profile.topics().clear());
        assertThrows(UnsupportedOperationException.class, () -> profile.urgentInstructions().clear());
    }

    @Test
    void profileEffectivenessRequiresApprovalAndAReachedEffectiveDate() {
        ServiceProfile profile = profile(List.of("collection"), List.of(new LocalizedText("en", "call us")));
        assertTrue(profile.effectiveAt(NOW));
        assertFalse(profile.effectiveAt(NOW.minusSeconds(1)));
        ServiceProfile draft = new ServiceProfile(profile.id(), profile.organizationId(), profile.unitId(),
                profile.version(), ProfileStatus.DRAFT, profile.hours(), profile.responseTargetMinutes(),
                profile.topics(), profile.urgentInstructions(), profile.queueOwner(), profile.backupCoverage(),
                profile.escalationChannel(), profile.urgentChannel(), profile.downtimeChannel(),
                profile.createdBy(), null, null, null, profile.effectiveFrom(), profile.createdAt());
        assertFalse(draft.effectiveAt(NOW));
        assertEquals(NOW.plusSeconds(1800), profile.dueAt(NOW));
    }

    @Test
    void aUnitIsEnabledOnlyWhenAnEffectiveProfileNamesAnOwnerAndBackup() {
        ServiceProfile base = profile(List.of("collection"), List.of(new LocalizedText("en", "call us")));
        assertTrue(base.enablesUnitAt(NOW));
        assertFalse(withStaffing(base, "   ", List.of("backup")).enablesUnitAt(NOW));
        assertFalse(withStaffing(base, null, List.of("backup")).enablesUnitAt(NOW));
        assertFalse(withStaffing(base, "nurse-lead", List.of()).enablesUnitAt(NOW));
        assertFalse(withStaffing(base, "nurse-lead", List.of("  ")).enablesUnitAt(NOW));
        assertFalse(base.enablesUnitAt(NOW.minusSeconds(1)));

        ServiceProfile draft = new ServiceProfile(base.id(), base.organizationId(), base.unitId(),
                base.version(), ProfileStatus.DRAFT, base.hours(), base.responseTargetMinutes(),
                base.topics(), base.urgentInstructions(), base.queueOwner(), base.backupCoverage(),
                base.escalationChannel(), base.urgentChannel(), base.downtimeChannel(),
                base.createdBy(), null, null, null, base.effectiveFrom(), base.createdAt());
        assertFalse(draft.enablesUnitAt(NOW));
    }

    @Test
    void zoneAcceptsAnIanaIdentifierAndRejectsOffsetsAndMalformedValues() {
        assertEquals(ZoneId.of("America/New_York"), ServiceProfileInputs.zone("America/New_York"));
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.zone("Z")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.zone("+02:00")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.zone(" ")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.zone("Not/AZone")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.zone(null)).status());
    }

    @Test
    void windowParsesDayAndTimesAndRejectsDegenerateOrMalformedValues() {
        ServiceWindow window = ServiceProfileInputs.window("FRIDAY|22:00|02:00");
        assertEquals(DayOfWeek.FRIDAY, window.day());
        assertEquals(LocalTime.of(22, 0), window.start());
        assertEquals(LocalTime.of(2, 0), window.end());
        assertTrue(window.wraps());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.window("MONDAY|09:00|09:00")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.window("MONDAY|09:00")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.window("NOTADAY|09:00|10:00")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.window("MONDAY|bad|10:00")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ServiceProfileInputs.window(null)).status());
    }

    @Test
    void aServiceWindowListMustContainAtLeastOneValidWindow() {
        assertEquals(1, ServiceProfileInputs.windows(List.of("MONDAY|09:00|17:00")).size());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.windows(List.of())).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.windows(null)).status());
    }

    @Test
    void minutesMustBePositiveAndBounded() {
        assertEquals(15, ServiceProfileInputs.minutes(15, "responseTargetMinutes", 1440));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.minutes(0, "responseTargetMinutes", 1440)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.minutes(-1, "responseTargetMinutes", 1440)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.minutes(1441, "responseTargetMinutes", 1440)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.minutes(null, "responseTargetMinutes", 1440)).status());
    }

    @Test
    void versionAllowsZeroForAnInitialPublishButRejectsInvalidValues() {
        assertEquals(0, ServiceProfileInputs.version(0, "expectedVersion"));
        assertEquals(7, ServiceProfileInputs.version(7L, "expectedVersion"));
        assertEquals(Integer.MAX_VALUE,
                ServiceProfileInputs.version((long) Integer.MAX_VALUE, "expectedVersion"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.version(null, "expectedVersion")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.version(-1, "expectedVersion")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.version((long) Integer.MAX_VALUE + 1, "expectedVersion")).status());
    }

    @Test
    void nonEmptyListsRejectNullEmptyOversizedAndBlankEntries() {
        assertEquals(List.of("a", "b"), ServiceProfileInputs.list(List.of(" a ", "b"), "topics", 5, 16));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.list(null, "topics", 5, 16)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.list(List.of(), "topics", 5, 16)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.list(List.of("a", "b", "c"), "topics", 2, 16)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.list(java.util.Arrays.asList("a", null), "topics", 5, 16)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.list(List.of("  "), "topics", 5, 16)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.list(List.of("toolongvalue"), "topics", 5, 4)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.list(List.of("MRN 12345678"), "topics", 5, 32)).status());
    }

    @Test
    void urgentInstructionsRequireSupportedLanguagesAndSafeNonSensitiveCopy() {
        List<LocalizedText> valid = List.of(new LocalizedText("en", "Call the unit"), new LocalizedText("ar", "اتصل بالوحدة"));
        assertEquals(2, ServiceProfileInputs.instructions(valid).size());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(List.of())).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(java.util.Arrays.asList((LocalizedText) null))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(List.of(new LocalizedText("fr", "bonjour")))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(List.of(new LocalizedText("en", "   ")))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(List.of(new LocalizedText("en", "MRN 12345678")))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(List.of(
                        new LocalizedText("en", "a"), new LocalizedText("ar", "b"),
                        new LocalizedText("en", "c"), new LocalizedText("ar", "d"),
                        new LocalizedText("en", "e")))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(List.of(new LocalizedText("en", "call us")))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instructions(List.of(new LocalizedText("ar", "اتصل")))).status());
    }

    @Test
    void effectiveFromIsParsedAsAnUtcInstant() {
        assertEquals(NOW, ServiceProfileInputs.instant("2026-11-19T12:00:00Z", "effectiveFrom"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instant("not-a-date", "effectiveFrom")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ServiceProfileInputs.instant(null, "effectiveFrom")).status());
    }

    @Test
    void actionMatrixSeparatesPatientSelfServiceFromAdministration() {
        ServiceProfileActions actions = new ServiceProfileActions();
        assertTrue(actions.allows("PROFILE_VIEW", "patient"));
        assertTrue(actions.allows("PROFILE_VIEW", "tenant_admin"));
        assertTrue(actions.allows("PROFILE_VIEW", "privacy_auditor"));
        assertTrue(actions.allows("PROFILE_VIEW", "unit_supervisor"));
        assertTrue(actions.allows("AVAILABILITY_VIEW", "patient"));
        assertTrue(actions.allows("AVAILABILITY_VIEW", "unit_supervisor"));
        assertTrue(actions.allows("PROFILE_PUBLISH", "tenant_admin"));
        assertTrue(actions.allows("PROFILE_APPROVE", "tenant_admin"));
        assertTrue(actions.allows("PROFILE_HISTORY", "tenant_admin"));
        assertTrue(actions.allows("PROFILE_HISTORY", "privacy_auditor"));
        assertTrue(actions.allows("SUPERVISOR_VIEW", "unit_supervisor"));
        assertTrue(actions.allows("SUPERVISOR_VIEW", "tenant_admin"));
        assertFalse(actions.allows("PROFILE_PUBLISH", "patient"));
        assertFalse(actions.allows("PROFILE_APPROVE", "patient"));
        assertFalse(actions.allows("SUPERVISOR_VIEW", "patient"));
        assertFalse(actions.allows("PROFILE_VIEW", "reception"));
        assertFalse(actions.allows("UNKNOWN", "tenant_admin"));
    }

    private static ServiceProfile withStaffing(ServiceProfile base, String owner, List<String> backups) {
        return new ServiceProfile(base.id(), base.organizationId(), base.unitId(), base.version(),
                base.status(), base.hours(), base.responseTargetMinutes(), base.topics(),
                base.urgentInstructions(), owner, backups, base.escalationChannel(),
                base.urgentChannel(), base.downtimeChannel(), base.createdBy(), base.approvedBy(),
                base.approvedAt(), base.approvalNote(), base.effectiveFrom(), base.createdAt());
    }

    private static ServiceProfile profile(List<String> topics, List<LocalizedText> instructions) {
        return new ServiceProfile("profile-1", "11111111-1111-1111-1111-111111111111",
                "cccccccc-cccc-cccc-cccc-cccccccccccc", 1, ProfileStatus.APPROVED,
                new ServiceHours(ZoneId.of("UTC"), List.of(
                        new ServiceWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)))),
                30, topics, instructions, "nurse-lead", List.of("nurse-backup"), "escalation-queue",
                "urgent-phone", "downtime-phone", "approver", "approver", NOW, "site-policy", NOW, NOW);
    }
}
