package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
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
import com.aurevia.messaging.service.ServiceProfileContract.PublishProfileRequest;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.messaging.service.ServiceProfileService;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-06 clause 1 acceptance: an enabled unit publishes service hours, response
 * targets, appropriate topics and urgent contact instructions, and the publish
 * command refuses the refusals this clause implies.
 *
 * <p>The rule is already implemented in {@code messaging.service}; this class is
 * a focused, synthetic proof of it through the same domain entry points the HTTP
 * adapter uses. The unit under test is fresh (no pre-seeded profile), so the
 * served profile can only be the one these tests publish. All identifiers,
 * staff subjects and guidance copy are synthetic; no patient data is present.</p>
 */
class EnabledUnitPublishServiceHoursResponseTargetsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_ORG_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String PATIENT_SUBJECT = "patient-user";
    private static final String PUBLISHER = "tenant-admin";
    private static final String APPROVER = "second-admin";
    private static final String ZONE = "UTC";
    private static final String PAST_EFFECTIVE_FROM = "2026-11-19T11:00:00Z";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryServiceProfileStore store;
    private ServiceProfileService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryServiceProfileStore();
        service = new ServiceProfileService(store, new ServiceProfileActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        // Active tenant_admin membership in the caller's org is required for
        // publish; without it authorize() would return 403 before the clause's
        // unit, version and field checks are reached.
        store.addMember(ORG, PUBLISHER, "tenant_admin", true);
        store.addUnit(ORG, UNIT);

        // publish() now enforces the site's approved-channel fold before any other
        // field check. Seed the codes this fixture publishes so the tests reach the
        // behaviour they assert (topics, instructions, version) instead of failing
        // closed at the UNAPPROVED_CHANNEL guard (MSG-06 clause 6).
        // Publish enforces site-approved urgent and downtime channels before it
        // parses the fields this clause proves. Seed the synthetic codes used by
        // these requests so the refusals below reach their intended validators.

        store.seedApprovedChannels(ORG, "msg06.approved_urgent_channels", "urgent-channel");
        store.seedApprovedChannels(ORG, "msg06.approved_downtime_channels", "downtime-channel");
        // The same local unit identifier in another tenant must stay isolated.
        store.addUnit(OTHER_ORG, OTHER_ORG_UNIT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Prove the rule is met: a named-approved, effective publish round-trips all
     * four field groups and only then enables the unit for patient messaging.
     */
    @Test
    void enabledUnitPublishesHoursTargetsTopicsAndUrgentInstructions() {
        TenantContext.set(new TenantContext(ORG, PUBLISHER, "ADMIN", "msg06-publish"));
        List<ServiceWindow> windows = List.of(
                new ServiceWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                new ServiceWindow(DayOfWeek.WEDNESDAY, LocalTime.of(13, 0), LocalTime.of(17, 0)));
        PublishProfileRequest request = new PublishProfileRequest(UNIT, ZONE,
                List.of("MONDAY|09:00|17:00", "WEDNESDAY|13:00|17:00"), 30L,
                "queue-owner", List.of("backup-nurse"), "escalation-channel",
                "urgent-channel", "downtime-channel",
                List.of("collection", "results"), instructions(),
                PAST_EFFECTIVE_FROM, 0);

        ServiceProfile draft = service.publish(request);
        assertEquals(ProfileStatus.DRAFT, draft.status());
        assertEquals(1, draft.version());

        // Approving requires a different named tenant_admin, so the publisher's
        // own approval cannot make the unit enabled.
        TenantContext.set(new TenantContext(ORG, APPROVER, "ADMIN", "msg06-approve"));
        store.addMember(ORG, APPROVER, "tenant_admin", true);
        ServiceProfile approved = service.approve(draft.id(), 1, "site-policy");
        assertEquals(ProfileStatus.APPROVED, approved.status());

        ServiceProfile served = service.viewProfile(UNIT);
        assertEquals(draft.id(), served.id());
        assertEquals(1, served.version());
        assertEquals(ProfileStatus.APPROVED, served.status());
        assertEquals(ZONE, served.hours().zone().getId());
        assertEquals(windows, served.hours().windows());
        assertEquals(30, served.responseTargetMinutes());
        assertEquals(NOW.plusSeconds(30L * 60), served.dueAt(NOW));
        assertEquals(List.of("collection", "results"), served.topics());
        assertEquals(instructions(), served.urgentInstructions());
        assertEquals("queue-owner", served.queueOwner());
        assertEquals(List.of("backup-nurse"), served.backupCoverage());
        assertEquals("urgent-channel", served.urgentChannel());
        assertEquals("downtime-channel", served.downtimeChannel());
        assertEquals("escalation-channel", served.escalationChannel());
        assertTrue(served.enablesUnitAt(NOW));
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit ->
                "PROFILE_PUBLISH".equals(audit.action()) && "SUCCESS".equals(audit.outcome())));
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit ->
                "PROFILE_VIEW".equals(audit.action()) && "SUCCESS".equals(audit.outcome())));
    }

    /** A role without publish permission cannot publish a profile (403). */
    @Test
    void publishIsRefusedForWrongRole() {
        store.addMember(ORG, PATIENT_SUBJECT, "patient", true);
        TenantContext.set(new TenantContext(ORG, PATIENT_SUBJECT, "PATIENT_MESSAGING", "msg06-wrong-role"));

        ApiException denial = assertThrows(ApiException.class, () -> service.publish(request(0)));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
    }

    /** A version that is not the latest is refused (412), never overwrite. */
    @Test
    void publishIsRefusedWithStaleVersion() {
        TenantContext.set(new TenantContext(ORG, PUBLISHER, "ADMIN", "msg06-first-publish"));
        service.publish(request(0));
        TenantContext.set(new TenantContext(ORG, PUBLISHER, "ADMIN", "msg06-stale-publish"));

        ApiException denial = assertThrows(ApiException.class, () -> service.publish(request(0)));
        assertEquals(412, denial.status());
        assertEquals("STALE_VERSION", denial.code());
    }

    /** A unit present only in another tenant is concealed from the publisher (404). */
    @Test
    void publishIsRefusedForAnotherTenantUnit() {
        TenantContext.set(new TenantContext(ORG, PUBLISHER, "ADMIN", "msg06-other-tenant"));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.publish(requestFor(OTHER_ORG_UNIT, 0)));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
    }

    /** The publish obligation itself is enforced: no topics or urgent copy, no publish (422). */
    @Test
    void publishIsRefusedWithoutTopicsOrUrgentInstructions() {
        TenantContext.set(new TenantContext(ORG, PUBLISHER, "ADMIN", "msg06-missing-fields"));

        PublishProfileRequest noTopics = new PublishProfileRequest(UNIT, ZONE,
                List.of("MONDAY|09:00|17:00"), 30L, "queue-owner", List.of("backup-nurse"),
                "escalation-channel", "urgent-channel", "downtime-channel",
                List.of(), instructions(), PAST_EFFECTIVE_FROM, 0);
        ApiException topicsDenial = assertThrows(ApiException.class, () -> service.publish(noTopics));
        assertEquals(422, topicsDenial.status());
        assertEquals("INVALID_TOPICS", topicsDenial.code());

        PublishProfileRequest noInstructions = new PublishProfileRequest(UNIT, ZONE,
                List.of("MONDAY|09:00|17:00"), 30L, "queue-owner", List.of("backup-nurse"),
                "escalation-channel", "urgent-channel", "downtime-channel",
                List.of("collection"), List.of(), PAST_EFFECTIVE_FROM, 0);
        ApiException instructionsDenial = assertThrows(ApiException.class,
                () -> service.publish(noInstructions));
        assertEquals(422, instructionsDenial.status());
        assertEquals("INVALID_URGENTINSTRUCTIONS", instructionsDenial.code());
    }

    private static PublishProfileRequest request(int expectedVersion) {
        return requestFor(UNIT, expectedVersion);
    }

    private static PublishProfileRequest requestFor(String unit, int expectedVersion) {
        return new PublishProfileRequest(unit, ZONE, List.of("MONDAY|09:00|17:00"), 30L,
                "queue-owner", List.of("backup-nurse"), "escalation-channel",
                "urgent-channel", "downtime-channel", List.of("collection", "results"),
                instructions(), PAST_EFFECTIVE_FROM, expectedVersion);
    }

    private static List<LocalizedText> instructions() {
        return List.of(new LocalizedText("en", "Call the care unit"),
                new LocalizedText("ar", "اتصل بوحدة الرعاية"));
    }
}
