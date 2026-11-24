package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.service.InMemoryServiceProfileStore;
import com.aurevia.messaging.service.ServiceProfileActions;
import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.messaging.service.ServiceProfileService;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-06 clause 2 proof: a connected sender can read a unit's published service
 * information through the same protected service call the pre-send view route
 * uses, before any message is composed. Negative cases show that an unapproved,
 * future-dated, unstaffed, foreign, unconnected or unauthorized unit is never
 * disclosed. Every organization, subject, unit and profile here is synthetic.
 */
class ShowTheseBeforeSendingTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String UNCONNECTED_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String UNPUBLISHED_UNIT = "dddddddd-eeee-ffff-aaaa-bbbbbbbbbbbb";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_SUBJECT = "patient-user";
    private static final String PUBLISHER = "tenant-admin";
    private static final String APPROVER = "approver-nurse";
    private static final String PROFILE_ID = "11111111-2222-3333-4444-555555555555";
    private static final String DRAFT_ID = "11111111-2222-3333-4444-666666666666";
    private static final String FUTURE_ID = "11111111-2222-3333-4444-777777777777";
    private static final String UNSTAFFED_ID = "11111111-2222-3333-4444-888888888888";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryServiceProfileStore store;
    private ServiceProfileService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryServiceProfileStore();
        service = new ServiceProfileService(store, new ServiceProfileActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, PATIENT_SUBJECT, "PATIENT_MESSAGING", "corr-show-before-send"));
        store.addMember(ORG, PATIENT_SUBJECT, "patient", true);
        store.addMember(OTHER_ORG, PATIENT_SUBJECT, "patient", true);
        store.addUnit(ORG, UNIT);
        store.addUnit(ORG, UNCONNECTED_UNIT);
        store.addUnit(OTHER_ORG, UNIT);
        store.linkPatient(ORG, PATIENT_SUBJECT, PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, true);
        store.addProfile(publishedProfile());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Happy path: the pre-send read exposes the full published profile and its audit row. */
    @Test
    void connectedPatientIsShownPublishedHoursTargetTopicsAndUrgentContactsBeforeSending() {
        ServiceProfile shown = service.viewProfile(UNIT);

        assertEquals(PROFILE_ID, shown.id());
        assertEquals(ORG, shown.organizationId());
        assertEquals(UNIT, shown.unitId());
        assertEquals(3, shown.version());
        assertEquals(ProfileStatus.APPROVED, shown.status());
        assertEquals("Europe/London", shown.hours().zone().getId());
        assertEquals(List.of(
                new ServiceWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                new ServiceWindow(DayOfWeek.WEDNESDAY, LocalTime.of(13, 0), LocalTime.of(18, 30))),
                shown.hours().windows());
        assertEquals(45, shown.responseTargetMinutes());
        assertEquals(List.of("collection", "imaging-preparation"), shown.topics());
        assertEquals(instructions(), shown.urgentInstructions());
        assertEquals("charge-nurse", shown.queueOwner());
        assertEquals(List.of("backup-one", "backup-two"), shown.backupCoverage());
        assertEquals("escalation-line", shown.escalationChannel());
        assertEquals("urgent-line", shown.urgentChannel());
        assertEquals("downtime-notice", shown.downtimeChannel());
        assertEquals(PUBLISHER, shown.createdBy());
        assertEquals(APPROVER, shown.approvedBy());
        assertEquals(NOW.minusSeconds(600), shown.approvedAt());
        assertEquals("site-policy", shown.approvalNote());
        assertEquals(NOW.minusSeconds(1800), shown.effectiveFrom());
        assertEquals(NOW.minusSeconds(3600), shown.createdAt());
        assertTrue(store.auditEvents(ORG).contains(new MessagingAudit(ORG, PATIENT_SUBJECT,
                ServiceProfileActions.PROFILE_VIEW, PROFILE_ID, "SUCCESS", NOW)));
    }

    /**
     * A draft, a future-dated version and an approved-but-unstaffed version are
     * each concealed; the last case proves the enablement filter, not an empty
     * store, produces the refusal.
     */
    @Test
    void draftOrFutureDatedProfileIsNeverShownToTheSender() {
        store.addUnit(ORG, UNPUBLISHED_UNIT);
        store.addConnection(ORG, PATIENT, UNPUBLISHED_UNIT, true);
        store.addProfile(new ServiceProfile(DRAFT_ID, ORG, UNPUBLISHED_UNIT, 1, ProfileStatus.DRAFT,
                hours(), 45, List.of("collection"), instructions(), "charge-nurse", List.of("backup-one"),
                "escalation-line", "urgent-line", "downtime-notice", PUBLISHER, null, null, null, NOW, NOW));
        store.addProfile(new ServiceProfile(FUTURE_ID, ORG, UNPUBLISHED_UNIT, 2, ProfileStatus.APPROVED,
                hours(), 45, List.of("collection"), instructions(), "charge-nurse", List.of("backup-one"),
                "escalation-line", "urgent-line", "downtime-notice", PUBLISHER, APPROVER, NOW,
                "site-policy", NOW.plusSeconds(3600), NOW));

        ApiException notYetPublished = assertThrows(ApiException.class,
                () -> service.viewProfile(UNPUBLISHED_UNIT));
        assertEquals(404, notYetPublished.status());
        assertEquals("NOT_FOUND", notYetPublished.code());

        // An approved version effective now still needs a named owner and backup.
        store.addProfile(new ServiceProfile(UNSTAFFED_ID, ORG, UNPUBLISHED_UNIT, 3, ProfileStatus.APPROVED,
                hours(), 45, List.of("collection"), instructions(), "  ", List.of("  "),
                "escalation-line", "urgent-line", "downtime-notice", PUBLISHER, APPROVER, NOW,
                "site-policy", NOW.minusSeconds(60), NOW));
        assertTrue(store.effectiveProfile(ORG, UNPUBLISHED_UNIT, NOW).isPresent());
        ApiException unstaffed = assertThrows(ApiException.class,
                () -> service.viewProfile(UNPUBLISHED_UNIT));
        assertEquals(404, unstaffed.status());
        assertEquals("NOT_FOUND", unstaffed.code());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "PROFILE_VIEW".equals(audit.action())
                && "NOT_FOUND".equals(audit.outcome()) && UNPUBLISHED_UNIT.equals(audit.resourceId())));
    }

    /** A patient with no connection, or with a revoked one, cannot see the service information. */
    @Test
    void patientWithoutAnActiveConnectionCannotSeeTheUnitsServiceInformation() {
        ApiException neverConnected = assertThrows(ApiException.class,
                () -> service.viewProfile(UNCONNECTED_UNIT));
        assertEquals(404, neverConnected.status());
        assertEquals("NOT_FOUND", neverConnected.code());

        store.addConnection(ORG, PATIENT, UNIT, false);
        ApiException revoked = assertThrows(ApiException.class, () -> service.viewProfile(UNIT));
        assertEquals(404, revoked.status());
        assertEquals("NOT_FOUND", revoked.code());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "PROFILE_VIEW".equals(audit.action())
                && "NOT_FOUND".equals(audit.outcome()) && UNIT.equals(audit.resourceId())));
    }

    /** Another tenant's unit is concealed, even when it shares a local identifier with ours. */
    @Test
    void aCrossTenantUnitIsConcealedEvenWithTheSameLocalIdentifier() {
        TenantContext.set(new TenantContext(OTHER_ORG, PATIENT_SUBJECT, "PATIENT_MESSAGING", "corr-other-org"));

        ApiException denial = assertThrows(ApiException.class, () -> service.viewProfile(UNIT));

        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertTrue(store.auditEvents(OTHER_ORG).stream().anyMatch(audit -> "PROFILE_VIEW".equals(audit.action())
                && "NOT_FOUND".equals(audit.outcome()) && UNIT.equals(audit.resourceId())));

        // A unit that exists only in the other tenant is concealed at the unit-scope check too.
        ApiException unknownThere = assertThrows(ApiException.class,
                () -> service.viewProfile(UNCONNECTED_UNIT));
        assertEquals(404, unknownThere.status());
        assertEquals("NOT_FOUND", unknownThere.code());
    }

    /** An active member whose role lacks the view action is refused and the denial is audited. */
    @Test
    void aRoleWithoutProfileViewPermissionIsDenied() {
        String receptionist = "reception-user";
        store.addMember(ORG, receptionist, "reception", true);
        TenantContext.set(new TenantContext(ORG, receptionist, "PATIENT_MESSAGING", "corr-no-view-role"));

        ApiException denial = assertThrows(ApiException.class, () -> service.viewProfile(UNIT));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "PROFILE_VIEW".equals(audit.action())
                && "DENIED".equals(audit.outcome()) && receptionist.equals(audit.subject())));
    }

    /** No request context means the read fails closed before any profile is resolved. */
    @Test
    void missingContextReturnsUnauthenticated() {
        TenantContext.clear();

        ApiException denial = assertThrows(ApiException.class, () -> service.viewProfile(UNIT));

        assertEquals(401, denial.status());
        assertEquals("UNAUTHENTICATED", denial.code());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    private static ServiceProfile publishedProfile() {
        return new ServiceProfile(PROFILE_ID, ORG, UNIT, 3, ProfileStatus.APPROVED, hours(), 45,
                List.of("collection", "imaging-preparation"), instructions(), "charge-nurse",
                List.of("backup-one", "backup-two"), "escalation-line", "urgent-line", "downtime-notice",
                PUBLISHER, APPROVER, NOW.minusSeconds(600), "site-policy", NOW.minusSeconds(1800),
                NOW.minusSeconds(3600));
    }

    private static ServiceHours hours() {
        return new ServiceHours(ZoneId.of("Europe/London"), List.of(
                new ServiceWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                new ServiceWindow(DayOfWeek.WEDNESDAY, LocalTime.of(13, 0), LocalTime.of(18, 30))));
    }

    private static List<LocalizedText> instructions() {
        return List.of(new LocalizedText("en", "Call the unit"), new LocalizedText("ar", "اتصل بالوحدة"));
    }
}
