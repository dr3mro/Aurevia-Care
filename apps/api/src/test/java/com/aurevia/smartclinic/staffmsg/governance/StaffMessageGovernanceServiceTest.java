package com.aurevia.smartclinic.staffmsg.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.ExportRequest;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.HideRequest;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.MessageView;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.RecordActionRequest;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.SearchPage;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.SearchQuery;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.StaffAction;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.AttachmentLink;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ScanState;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Acceptance for the staff-message governance service (MSG-07).
 *
 * <p>Every case runs against the synthetic tenant-scoped store with an injected
 * clock. Evidence is executed unit tests, never a clinical reviewer.</p>
 */
class StaffMessageGovernanceServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String NO_POLICY_ORG = "33333333-3333-3333-3333-333333333333";
    private static final String CONV = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String CONV2 = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String CONV3 = "aaaaaaaa-0000-0000-0000-000000000003";
    private static final String NO_POLICY_CONV = "aaaaaaaa-0000-0000-0000-000000000004";
    private static final String FOREIGN_CONV = "aaaaaaaa-0000-0000-0000-000000000009";
    private static final String M1 = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String M2 = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String M3 = "bbbbbbbb-0000-0000-0000-000000000003";
    private static final String M4 = "bbbbbbbb-0000-0000-0000-000000000004";
    private static final String FOREIGN_MESSAGE = "bbbbbbbb-0000-0000-0000-000000000009";
    private static final String HOLD = "cccccccc-0000-0000-0000-000000000001";
    private static final String MISSING = "dddddddd-0000-0000-0000-000000000001";
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final ExportProvenance PROVENANCE = new ExportProvenance(CONV, "rev-2", "record_access");

    private InMemoryStaffMessageGovernanceStore store;
    private StaffMessageGovernanceService service;

    /** Seed two tenants, three conversations and synthetic messages. */
    @BeforeEach
    void setUp() {
        store = new InMemoryStaffMessageGovernanceStore();
        store.addMember(ORG, "nurse-a", "unit_staff", true);
        store.addMember(ORG, "clinician", "clinician", true);
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        store.addMember(ORG, "admin", "tenant_admin", true);
        store.addMember(ORG, "inactive", "unit_staff", false);
        store.addMember(OTHER_ORG, "nurse-a", "unit_staff", true);
        store.addMember(OTHER_ORG, "auditor", "privacy_auditor", true);
        store.addMember(NO_POLICY_ORG, "auditor", "privacy_auditor", true);

        store.addConversation(ORG, CONV, NOW.minus(Duration.ofDays(40)));
        store.addConversation(ORG, CONV2, NOW.minus(Duration.ofDays(10)));
        store.addConversation(ORG, CONV3, NOW.minus(Duration.ofDays(40)));
        store.addConversation(ORG, NO_POLICY_CONV, NOW.minus(Duration.ofDays(40)));
        store.addConversation(OTHER_ORG, FOREIGN_CONV, NOW.minus(Duration.ofDays(40)));
        store.addConversation(NO_POLICY_ORG, NO_POLICY_CONV, NOW.minus(Duration.ofDays(40)));
        for (String conversation : List.of(CONV, CONV2, NO_POLICY_CONV)) {
            store.addParticipant(ORG, conversation, "nurse-a");
        }
        store.addParticipant(ORG, CONV3, "clinician");
        store.addParticipant(OTHER_ORG, FOREIGN_CONV, "nurse-a");

        store.addMessage(ORG, new MessageView(M1, CONV, "clinician", "Lab review",
                NOW.minus(Duration.ofMinutes(60)), "Please review the lab results"));
        store.addMessage(ORG, new MessageView(M2, CONV, "nurse-a", "Shift handover",
                NOW.minus(Duration.ofMinutes(120)), "Handover notes for ward"));
        store.addMessage(ORG, new MessageView(M3, CONV2, "nurse-a", "Pharmacy",
                NOW.minus(Duration.ofMinutes(180)), "Medication ready"));
        store.addMessage(ORG, new MessageView(M4, CONV3, "clinician", "Private",
                NOW.minus(Duration.ofMinutes(60)), "Clinician only content"));
        store.addMessage(OTHER_ORG, new MessageView(FOREIGN_MESSAGE, FOREIGN_CONV, "nurse-a", "Foreign",
                NOW.minus(Duration.ofMinutes(60)), "Foreign tenant content"));
        store.putPolicy(ORG, "conversation", 30);

        service = new StaffMessageGovernanceService(store, new StaffMessageGovernanceActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        as("nurse-a", ORG);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void as(String subject, String organizationId) {
        TenantContext.set(new TenantContext(organizationId, subject, "STAFFMSG_GOVERNANCE", "corr-1"));
    }

    private AuditEvent lastAudit() {
        List<AuditEvent> events = store.auditEvents(ORG);
        return events.get(events.size() - 1);
    }

    @Test
    void recordActionAuditsEveryActionWithAServerDerivedOutcome() {
        for (StaffAction action : List.of(StaffAction.OPEN, StaffAction.SEND, StaffAction.FORWARD,
                StaffAction.ATTACH, StaffAction.DOWNLOAD, StaffAction.FOLDER_MOVE, StaffAction.DELETE)) {
            as("nurse-a", ORG);
            AuditEvent recorded = service.recordAction(new RecordActionRequest(action.wire(), CONV, "facility"));
            assertEquals(action, recorded.action());
            assertEquals(Outcome.SUCCESS, recorded.outcome());
            assertEquals("STAFFMSG_GOVERNANCE", recorded.purpose());
            assertEquals("corr-1", recorded.correlationId());
            assertEquals(NOW, recorded.occurredAt());
            for (var component : recorded.getClass().getRecordComponents()) {
                assertFalse("body".equals(component.getName()));
                assertFalse("checksum".equals(component.getName()));
            }
        }
        assertEquals(7, store.auditEvents(ORG).size());
    }

    @Test
    void recordActionRejectsGovernanceCommandsItCannotPerform() {
        // Governance commands are performed and audited only by their dedicated
        // endpoints. Accepting them here would fabricate a SUCCESS fact for work
        // that never happened (OPS-02), even for a records officer.
        as("auditor", ORG);
        for (StaffAction action : List.of(StaffAction.HOLD_PLACE, StaffAction.HOLD_RELEASE,
                StaffAction.RETENTION_APPLY, StaffAction.EXPORT, StaffAction.AUDIT_READ)) {
            ApiException error = assertThrows(ApiException.class, () -> service.recordAction(
                    new RecordActionRequest(action.wire(), CONV, "facility")), action.name());
            assertEquals(422, error.status(), action.name());
            assertEquals("INVALID_ACTION", error.code(), action.name());
        }
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void recordActionRejectsNullAndUnknownActionsBeforeAnyAudit() {
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class, () -> service.recordAction(
                new RecordActionRequest(null, CONV, "facility"))).code());
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class, () -> service.recordAction(
                new RecordActionRequest("guess", CONV, "facility"))).code());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void recordActionDeniesInactiveMembershipAndWrongRoleAndAuditsDenials() {
        as("inactive", ORG);
        ApiException inactive = assertThrows(ApiException.class, () -> service.recordAction(
                new RecordActionRequest("send", CONV, "facility")));
        assertEquals(403, inactive.status());
        assertEquals(Outcome.DENIED, lastAudit().outcome());

        as("auditor", ORG);
        ApiException wrongRole = assertThrows(ApiException.class, () -> service.recordAction(
                new RecordActionRequest("send", CONV, "facility")));
        assertEquals(403, wrongRole.status());
        assertEquals(StaffAction.SEND, lastAudit().action());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, () -> service.recordAction(
                new RecordActionRequest("send", CONV, "facility"))).status());
    }

    @Test
    void hideMessageSetsTrashWithoutDeletingTheDurableRow() {
        AuditEvent audit = service.hideMessage(new HideRequest(M1, CONV));
        assertEquals(StaffAction.DELETE, audit.action());
        assertEquals(Outcome.SUCCESS, audit.outcome());
        assertEquals(M1, audit.resourceId());
        assertEquals(MailFolder.TRASH, store.folders(ORG, "nurse-a").get(M1));
        assertTrue(store.messages(ORG).stream().anyMatch(message -> M1.equals(message.messageId())),
                "a user hide must retain the message for retention and legal hold");
    }

    @Test
    void hideMessageConcealsUnknownConversationMessageAndNonParticipant() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.hideMessage(new HideRequest(M1, MISSING))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.hideMessage(new HideRequest(MISSING, CONV))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.hideMessage(new HideRequest(M3, CONV))).status(),
                "a message in a different conversation cannot be hidden through this one");

        as("clinician", ORG);
        ApiException forbidden = assertThrows(ApiException.class,
                () -> service.hideMessage(new HideRequest(M1, CONV)));
        assertEquals(403, forbidden.status());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
    }

    @Test
    void retentionDecisionDistinguishesEligibleWindowHoldAndMissingResource() {
        as("auditor", ORG);
        RetentionDecision eligible = service.retentionDecision(CONV);
        assertTrue(eligible.eligible());
        assertEquals("ELIGIBLE", eligible.reason());

        assertEquals("RETENTION_WINDOW", service.retentionDecision(CONV2).reason());
        service.placeHold(new PlaceHoldRequest(CONV, "Synthetic dispute"));
        assertEquals("LEGAL_HOLD", service.retentionDecision(CONV).reason());
        assertEquals(404, assertThrows(ApiException.class, () -> service.retentionDecision(MISSING)).status());
    }

    @Test
    void retentionDecisionFailsClosedWithoutAnApprovedPolicy() {
        as("auditor", NO_POLICY_ORG);
        ApiException error = assertThrows(ApiException.class, () -> service.retentionDecision(NO_POLICY_CONV));
        assertEquals(422, error.status());
        assertEquals("NO_RETENTION_POLICY", error.code());
        List<AuditEvent> denied = store.auditEvents(NO_POLICY_ORG);
        assertEquals(Outcome.DENIED, denied.get(denied.size() - 1).outcome());
    }

    @Test
    void holdsOnOtherResourcesAndReleasedHoldsDoNotBlockRetention() {
        as("auditor", ORG);
        store.putHold(ORG, new LegalHold(HOLD, ORG, CONV2, "other dispute", "auditor", NOW,
                null, null, null));
        store.putHold(ORG, new LegalHold("cccccccc-0000-0000-0000-000000000002", ORG, CONV,
                "old dispute", "auditor", NOW, "auditor", NOW, "resolved"));
        assertTrue(service.retentionDecision(CONV).eligible());
        assertTrue(service.placeHold(new PlaceHoldRequest(CONV, "Synthetic dispute")).active());
        assertEquals("LEGAL_HOLD", service.retentionDecision(CONV).reason());
    }

    @Test
    void placeHoldRejectsDuplicateAndUnknownConversations() {
        as("auditor", ORG);
        LegalHold hold = service.placeHold(new PlaceHoldRequest(CONV, "Synthetic dispute"));
        assertTrue(hold.active());
        assertEquals("auditor", hold.placedBy());
        assertEquals(NOW, hold.placedAt());
        assertEquals(StaffAction.HOLD_PLACE, lastAudit().action());

        ApiException duplicate = assertThrows(ApiException.class,
                () -> service.placeHold(new PlaceHoldRequest(CONV, "Second reason")));
        assertEquals(409, duplicate.status());
        assertEquals("HOLD_EXISTS", duplicate.code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.placeHold(new PlaceHoldRequest(MISSING, "Synthetic dispute"))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());
    }

    @Test
    void releaseHoldRecordsTheActorAndRejectsUnknownAndRepeatReleases() {
        as("auditor", ORG);
        LegalHold hold = service.placeHold(new PlaceHoldRequest(CONV, "Synthetic dispute"));
        LegalHold released = service.releaseHold(hold.id(), "Dispute resolved");
        assertFalse(released.active());
        assertEquals("auditor", released.releasedBy());
        assertEquals(NOW, released.releasedAt());
        assertEquals("Synthetic dispute", released.reason());
        assertEquals(StaffAction.HOLD_RELEASE, lastAudit().action());

        assertEquals("HOLD_ALREADY_RELEASED", assertThrows(ApiException.class,
                () -> service.releaseHold(hold.id(), "Again")).code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.releaseHold(MISSING, "Unknown")).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());
    }

    @Test
    void searchFiltersBySenderSubjectTextAndDate() {
        SearchPage bySender = service.search(new SearchQuery("clinician", null, null, null, null, null, 10));
        assertEquals(List.of(M1), ids(bySender));

        assertEquals(List.of(M1), ids(service.search(new SearchQuery(null, "lab", null, null, null, null, 10))));
        assertTrue(ids(service.search(new SearchQuery(null, "missing", null, null, null, null, 10))).isEmpty());
        assertEquals(List.of(M2), ids(service.search(new SearchQuery(null, null, null, null, "handover", null, 10))));
        assertTrue(ids(service.search(new SearchQuery(null, null, null, null, "nomatch", null, 10))).isEmpty());

        assertEquals(List.of(M1), ids(service.search(new SearchQuery(null, null,
                NOW.minus(Duration.ofMinutes(90)), NOW.minus(Duration.ofMinutes(30)), null, null, 10))));
        assertEquals(List.of(M3), ids(service.search(new SearchQuery(null, null,
                null, NOW.minus(Duration.ofMinutes(150)), null, null, 10))));
        assertEquals(List.of(M1), ids(service.search(new SearchQuery(null, null,
                NOW.minus(Duration.ofMinutes(90)), null, null, null, 10))));
        assertTrue(store.auditEvents(ORG).stream()
                .anyMatch(event -> event.action() == StaffAction.AUDIT_READ));
    }

    @Test
    void searchNeverReturnsOtherTenantOtherUserOrHiddenMessages() {
        assertFalse(ids(service.search(new SearchQuery(null, null, null, null, null, null, 10)))
                .contains(FOREIGN_MESSAGE), "a foreign tenant message is never searchable");
        assertFalse(ids(service.search(new SearchQuery(null, null, null, null, null, null, 10)))
                .contains(M4), "another user's conversation is never searchable");
        store.setFolder(ORG, M2, "nurse-a", MailFolder.TRASH);
        assertFalse(ids(service.search(new SearchQuery(null, null, null, null, null, null, 10)))
                .contains(M2), "a hidden message is never searchable");
    }

    @Test
    void searchSkipsMessagesWithoutASubjectAndReportsTheVisibleFolder() {
        store.addMessage(ORG, new MessageView("bbbbbbbb-0000-0000-0000-000000000099", CONV, "nurse-a",
                null, NOW.minus(Duration.ofMinutes(30)), "no subject content"));
        assertTrue(ids(service.search(new SearchQuery(null, "anything", null, null, null, null, 10))).isEmpty());
        store.setFolder(ORG, M1, "nurse-a", MailFolder.SENT);
        SearchPage page = service.search(new SearchQuery(null, null, null, null, null, null, 10));
        assertEquals("sent", page.hits().stream().filter(hit -> M1.equals(hit.messageId()))
                .findFirst().orElseThrow().folder());
        assertEquals("inbox", page.hits().stream().filter(hit -> M2.equals(hit.messageId()))
                .findFirst().orElseThrow().folder());
    }

    @Test
    void searchValidatesBoundsBeforeAnyQuery() {
        assertEquals("INVALID_LIMIT", assertThrows(ApiException.class,
                () -> service.search(new SearchQuery(null, null, null, null, null, null, null))).code());
        assertEquals("INVALID_LIMIT", assertThrows(ApiException.class,
                () -> service.search(new SearchQuery(null, null, null, null, null, null, 0))).code());
        assertEquals("INVALID_LIMIT", assertThrows(ApiException.class,
                () -> service.search(new SearchQuery(null, null, null, null, null, null, 101))).code());
        assertEquals("INVALID_CURSOR", assertThrows(ApiException.class,
                () -> service.search(new SearchQuery(null, null, null, null, null, "  ", 10))).code());
        assertEquals("INVALID_CURSOR", assertThrows(ApiException.class,
                () -> service.search(new SearchQuery(null, null, null, null, null, "x".repeat(129), 10))).code());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void searchPaginatesWithAStableCursorAndAnEmptyUnknownCursor() {
        SearchPage first = service.search(new SearchQuery(null, null, null, null, null, null, 2));
        assertEquals(2, first.hits().size());
        assertEquals(M2, first.nextCursor());
        SearchPage second = service.search(new SearchQuery(null, null, null, null, null, first.nextCursor(), 2));
        assertEquals(List.of(M3), ids(second));
        assertEquals(null, second.nextCursor());
        assertTrue(ids(service.search(new SearchQuery(null, null, null, null, null, MISSING, 2))).isEmpty());
    }

    @Test
    void exportRequiresARecordsOfficerAndValidProvenance() {
        ApiException wrongRole = assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(CONV, PROVENANCE)));
        assertEquals(403, wrongRole.status());
        assertEquals(Outcome.DENIED, lastAudit().outcome());

        as("auditor", ORG);
        assertEquals("INVALID_PROVENANCE", assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(CONV, null))).code());
        assertEquals("INVALID_SOURCEREFERENCE", assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(CONV,
                        new ExportProvenance("not-a-uuid", "rev-2", "record_access")))).code());
        assertEquals("INVALID_SOURCEREVISION", assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(CONV,
                        new ExportProvenance(CONV, "bad revision", "record_access")))).code());
        assertEquals("INVALID_PURPOSE", assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(CONV,
                        new ExportProvenance(CONV, "rev-2", "free text")))).code());
        assertEquals(404, assertThrows(ApiException.class, () -> service.exportConversation(
                new ExportRequest(FOREIGN_CONV, new ExportProvenance(FOREIGN_CONV, "rev-2", "record_access")))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.exportConversation(
                new ExportRequest(MISSING, new ExportProvenance(MISSING, "rev-2", "record_access")))).status());
        // A provenance source cannot name a different conversation than the
        // one being exported, even when that source exists in the tenant.
        assertEquals("INVALID_PROVENANCE", assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(MISSING,
                        new ExportProvenance(CONV, "rev-2", "record_access")))).code());
        // Provenance must trace the copy back to the conversation it was made from.
        assertEquals("INVALID_PROVENANCE", assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(CONV2,
                        new ExportProvenance(CONV, "rev-2", "record_access")))).code());
    }

    @Test
    void exportConversationCarriesEveryAttachmentChecksum() {
        store.addLink(ORG, M1, new AttachmentLink(ORG, "cccccccc-0000-0000-0000-000000000011", M1,
                "doc-1", "sha-1", ScanState.CLEAN));
        store.addLink(ORG, M2, new AttachmentLink(ORG, "cccccccc-0000-0000-0000-000000000012", M2,
                "doc-2", "sha-2", ScanState.CLEAN));
        as("auditor", ORG);
        ConversationExport export = service.exportConversation(new ExportRequest(CONV, PROVENANCE));
        assertEquals(List.of("sha-1", "sha-2"), export.checksums());
        assertEquals(PROVENANCE, export.provenance());
        assertEquals("auditor", export.createdBy());
        assertEquals(NOW.plusSeconds(3600), export.expiresAt());
        assertEquals(1, store.exports(ORG).size());
        assertEquals(PROVENANCE, store.exports(ORG).get(0).provenance());
        assertEquals(StaffAction.EXPORT, lastAudit().action());
    }

    @Test
    void auditTrailReadsTenantEventsAndAuditsItsOwnRead() {
        service.recordAction(new RecordActionRequest("open", CONV, "facility"));
        as("auditor", ORG);
        List<AuditEvent> all = service.auditTrail(null);
        assertTrue(all.stream().anyMatch(event -> event.action() == StaffAction.AUDIT_READ));
        assertEquals(2, all.size());
        List<AuditEvent> filtered = service.auditTrail(CONV);
        assertTrue(filtered.stream().allMatch(event -> CONV.equals(event.resourceId())));
        assertEquals(2, filtered.size(), "the audit-of-read is itself scoped to the requested resource");
        assertEquals("INVALID_RESOURCEID", assertThrows(ApiException.class,
                () -> service.auditTrail("not-a-uuid")).code());
    }

    @Test
    void crossTenantRecordsAreConcealed() {
        as("auditor", OTHER_ORG);
        assertEquals(404, assertThrows(ApiException.class, () -> service.retentionDecision(CONV)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.placeHold(new PlaceHoldRequest(CONV, "dispute"))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.exportConversation(new ExportRequest(CONV, PROVENANCE))).status());
        as("nurse-a", OTHER_ORG);
        assertTrue(ids(service.search(new SearchQuery(null, null, null, null, null, null, 10)))
                .contains(FOREIGN_MESSAGE));
        assertFalse(ids(service.search(new SearchQuery(null, null, null, null, null, null, 10)))
                .contains(M1));
        assertNotNull(store.auditEvents(OTHER_ORG));
    }

    private static List<String> ids(SearchPage page) {
        return page.hits().stream().map(hit -> hit.messageId()).toList();
    }
}
