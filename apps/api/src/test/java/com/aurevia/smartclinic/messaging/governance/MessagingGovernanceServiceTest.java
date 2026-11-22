package com.aurevia.smartclinic.messaging.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CreateExportRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LinkToChartRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RecordActionRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RequestCopyRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Acceptance for the MSG-07 governance service and its thin HTTP adapter.
 *
 * <p>Every case runs against the synthetic tenant-scoped store with an injected
 * clock. Evidence is from executed unit tests, not a clinical reviewer.</p>
 */
class MessagingGovernanceServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String RESOURCE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String RECENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String ELIGIBLE = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String UNPOLICED = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String CONNECTION = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String ACTIVE_CONNECTION = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String UNIT = "12345678-90ab-cdef-1234-567890abcdef";
    private static final String CONV = "c0ffee11-1111-1111-1111-111111111111";
    private static final String MSG = "d0ffee11-1111-1111-1111-111111111111";
    private static final String MISSING = "abcdef01-2345-6789-abcd-ef0123456789";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final ExportProvenance PROVENANCE =
            new ExportProvenance(RESOURCE, "rev-2", "record_access");

    private InMemoryMessagingGovernanceStore store;
    private MessagingGovernanceService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, "patient-user", "patient", true);
        store.addMember(ORG, "clinician", "clinician", true);
        store.addMember(ORG, "admin", "tenant_admin", true);
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        store.addMember(ORG, "inactive", "unit_staff", false);
        store.addMember(OTHER_ORG, "admin", "tenant_admin", true);
        store.addResource(ORG, RESOURCE, "conversation", NOW.minus(Duration.ofDays(40)));
        store.addResource(ORG, RECENT, "conversation", NOW.minus(Duration.ofDays(10)));
        store.addResource(ORG, ELIGIBLE, "conversation", NOW.minus(Duration.ofDays(31)));
        store.addResource(ORG, UNPOLICED, "observation", NOW.minus(Duration.ofDays(90)));
        store.putRetentionPolicy(ORG, "conversation", 30);
        store.addRecordAccessPolicy(ORG, true, "policy-v1");
        store.addConnection(ORG, CONNECTION, UNIT, PATIENT, NOW.minus(Duration.ofMinutes(5)));
        store.addConnection(ORG, ACTIVE_CONNECTION, UNIT, PATIENT, null);
        service = new MessagingGovernanceService(store, new MessagingGovernanceActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        as("clinician", ORG);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void as(String subject, String organizationId) {
        TenantContext.set(new TenantContext(organizationId, subject, "MSG_GOVERNANCE", "corr-1"));
    }

    private AuditEvent lastAudit() {
        List<AuditEvent> events = store.auditEvents(ORG);
        return events.get(events.size() - 1);
    }

    @Test
    void recordActionAuditsEveryConversationAndFileAction() {
        AuditAction[] actions = {AuditAction.OPEN, AuditAction.VIEW, AuditAction.SEND,
                AuditAction.ASSIGN, AuditAction.DOWNLOAD, AuditAction.ACKNOWLEDGE,
                AuditAction.FILE, AuditAction.CORRECT, AuditAction.RESTRICT};
        for (AuditAction action : actions) {
            as(action == AuditAction.ASSIGN || action == AuditAction.RESTRICT ? "admin" : "clinician", ORG);
            AuditEvent recorded = service.recordAction(
                    new RecordActionRequest(action.wire(), RESOURCE, UNIT));
            assertEquals(action, recorded.action());
            assertEquals(Outcome.SUCCESS, recorded.outcome());
            assertEquals("MSG_GOVERNANCE", recorded.purpose());
            assertEquals("corr-1", recorded.correlationId());
            assertEquals(NOW, recorded.occurredAt());
        }
        assertEquals(actions.length, store.auditEvents(ORG).size());
        assertFalse(store.auditEvents(OTHER_ORG).stream()
                .anyMatch(event -> event.organizationId().equals(ORG)));
    }

    @Test
    void recordActionDeniesInactiveMembershipAndAuditsTheDenial() {
        as("inactive", ORG);
        ApiException error = assertThrows(ApiException.class, () -> service.recordAction(
                new RecordActionRequest("view", RESOURCE, UNIT)));
        assertEquals(403, error.status());
        assertEquals("FORBIDDEN", error.code());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
        assertEquals(AuditAction.VIEW, lastAudit().action());
    }

    @Test
    void recordActionRejectsUnknownActionAndDerivesOutcomeServerSide() {
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class, () -> service.recordAction(
                new RecordActionRequest("guess", RESOURCE, UNIT))).code());
        assertEquals(0, store.auditEvents(ORG).size());
        // No caller-supplied outcome exists: an accepted action records SUCCESS.
        AuditEvent recorded = service.recordAction(new RecordActionRequest("view", RESOURCE, UNIT));
        assertEquals(Outcome.SUCCESS, recorded.outcome());
    }

    @Test
    void recordActionRejectsGovernanceCommandsItCannotPerform() {
        // These actions are only genuinely performed through their dedicated
        // commands (placeHold, createExport, linkToChart, requestCopy, ...).
        // Recording them through the generic recorder would fabricate a
        // SUCCESS audit for work that never happened (OPS-02).
        AuditAction[] governance = {AuditAction.EXPORT, AuditAction.HOLD_PLACE,
                AuditAction.HOLD_RELEASE, AuditAction.CHART_LINK, AuditAction.COPY_REQUEST,
                AuditAction.RETENTION_APPLY, AuditAction.AUDIT_READ};
        // An admin has the permissions for all of these, so authorization is
        // not what must stop them: the recorder itself must refuse them.
        as("admin", ORG);
        for (AuditAction action : governance) {
            ApiException error = assertThrows(ApiException.class, () -> service.recordAction(
                    new RecordActionRequest(action.wire(), RESOURCE, UNIT)));
            assertEquals(422, error.status(), action.toString());
            assertEquals("INVALID_ACTION", error.code(), action.toString());
        }
        assertEquals(0, store.auditEvents(ORG).size(),
                "no fabricated governance audit fact may be appended");
    }

    @Test
    void auditTrailReadsTenantEventsAndAuditsItsOwnRead() {
        service.recordAction(new RecordActionRequest("view", RESOURCE, UNIT));
        as("auditor", ORG);
        List<AuditEvent> all = service.auditTrail(null);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(event -> event.action() == AuditAction.AUDIT_READ));
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());

        List<AuditEvent> filtered = service.auditTrail(RESOURCE);
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(event -> RESOURCE.equals(event.resourceId())));
        assertTrue(store.auditEvents(ORG, UNIT).isEmpty());
    }

    @Test
    void auditTrailDeniesPatientAndAuditsTheDenial() {
        as("patient-user", ORG);
        ApiException error = assertThrows(ApiException.class, () -> service.auditTrail(null));
        assertEquals(403, error.status());
        assertEquals(AuditAction.AUDIT_READ, lastAudit().action());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
    }

    @Test
    void placeHoldRequiresAnExistingResourceAndBlocksDuplicates() {
        as("auditor", ORG);
        LegalHold hold = service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        assertTrue(hold.active());
        assertEquals("Synthetic dispute", hold.reason());
        assertEquals("auditor", hold.placedBy());
        assertEquals(NOW, hold.placedAt());
        assertEquals(AuditAction.HOLD_PLACE, lastAudit().action());

        ApiException duplicate = assertThrows(ApiException.class,
                () -> service.placeHold(new PlaceHoldRequest(RESOURCE, "Second reason")));
        assertEquals(409, duplicate.status());
        assertEquals("HOLD_EXISTS", duplicate.code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());

        ApiException missing = assertThrows(ApiException.class,
                () -> service.placeHold(new PlaceHoldRequest(MISSING, "Synthetic dispute")));
        assertEquals(404, missing.status());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());
    }

    @Test
    void placeHoldDeniesPatients() {
        as("patient-user", ORG);
        ApiException error = assertThrows(ApiException.class,
                () -> service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute")));
        assertEquals(403, error.status());
    }

    @Test
    void releaseHoldRecordsTheReleasingActorAndRejectsRepeats() {
        as("auditor", ORG);
        LegalHold hold = service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        LegalHold released = service.releaseHold(hold.id(), "Dispute resolved");
        assertFalse(released.active());
        assertEquals("auditor", released.releasedBy());
        assertEquals(NOW, released.releasedAt());
        assertEquals(AuditAction.HOLD_RELEASE, lastAudit().action());

        assertEquals("HOLD_ALREADY_RELEASED", assertThrows(ApiException.class,
                () -> service.releaseHold(hold.id(), "Again")).code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.releaseHold(MISSING, "Unknown")).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());
    }

    @Test
    void retentionDecisionDistinguishesHoldWindowAndEligibleResources() {
        as("auditor", ORG);
        RetentionDecision eligible = service.retentionDecision(RESOURCE);
        assertTrue(eligible.eligible());
        assertEquals("ELIGIBLE", eligible.reason());
        assertEquals(RESOURCE, eligible.resourceId());

        service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        RetentionDecision held = service.retentionDecision(RESOURCE);
        assertFalse(held.eligible());
        assertEquals("LEGAL_HOLD", held.reason());

        RetentionDecision inWindow = service.retentionDecision(RECENT);
        assertFalse(inWindow.eligible());
        assertEquals("RETENTION_WINDOW", inWindow.reason());

        ApiException unknownPolicy = assertThrows(ApiException.class,
                () -> service.retentionDecision(UNPOLICED));
        assertEquals(422, unknownPolicy.status());
        assertEquals("NO_RETENTION_POLICY", unknownPolicy.code());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.retentionDecision(MISSING)).status());
    }

    @Test
    void applyRetentionSkipsHeldAndRecentResources() {
        as("auditor", ORG);
        service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        List<String> expired = service.applyRetention(NOW);
        assertEquals(List.of(ELIGIBLE), expired);
        assertEquals(AuditAction.RETENTION_APPLY, lastAudit().action());
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());

        as("patient-user", ORG);
        assertEquals(403, assertThrows(ApiException.class, () -> service.applyRetention(NOW)).status());
    }

    @Test
    void createExportBoundsScopeAndTtl() {
        as("auditor", ORG);
        ExportRecord export = service.createExport(new CreateExportRequest("conversation", 600L, PROVENANCE));
        assertEquals("conversation", export.scope());
        assertEquals("auditor", export.createdBy());
        assertEquals(NOW.plusSeconds(600), export.expiresAt());
        assertEquals(PROVENANCE, export.provenance());
        assertEquals(AuditAction.EXPORT, lastAudit().action());

        assertEquals("INVALID_TTL", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 10L, PROVENANCE))).code());
        assertEquals("INVALID_TTL", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 90_000L, PROVENANCE))).code());
        assertEquals("INVALID_SCOPE", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("  ", 600L, PROVENANCE))).code());
        assertEquals("INVALID_SCOPE", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("diagnosis free text", 600L, PROVENANCE))).code());
        // A null TTL uses the server default without polluting the audit scope.
        ExportRecord defaulted = service.createExport(new CreateExportRequest("audit", null, PROVENANCE));
        assertEquals(NOW.plusSeconds(3600), defaulted.expiresAt());

        as("patient-user", ORG);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L, PROVENANCE))).status());
    }

    @Test
    void createExportRejectsMissingProvenance() {
        as("auditor", ORG);
        ApiException error = assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L, null)));
        assertEquals(422, error.status());
        assertEquals("INVALID_PROVENANCE", error.code());
        assertEquals(0, store.auditEvents(ORG).size(),
                "a rejected export must not fabricate a successful audit fact");
    }

    @Test
    void createExportRejectsBlankOrOverlongProvenanceFields() {
        as("auditor", ORG);
        assertEquals("INVALID_SOURCEREFERENCE", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L,
                        new ExportProvenance("  ", "rev-2", "record_access")))).code());
        assertEquals("INVALID_SOURCEREVISION", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L,
                        new ExportProvenance(RESOURCE, null, "record_access")))).code());
        assertEquals("INVALID_PURPOSE", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L,
                        new ExportProvenance(RESOURCE, "rev-2", "x".repeat(129))))).code());
        assertEquals(0, store.auditEvents(ORG).size());
    }

    @Test
    void createExportBindsProvenanceToATrackedSourceResource() {
        as("auditor", ORG);
        ApiException untracked = assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L,
                        new ExportProvenance(MISSING, "rev-2", "record_access"))));
        assertEquals(404, untracked.status());
        assertEquals("NOT_FOUND", untracked.code());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());

        assertEquals("INVALID_PURPOSE", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L,
                        new ExportProvenance(RESOURCE, "rev-2", "free text with a patient name")))).code());
        assertEquals("INVALID_SOURCEREVISION", assertThrows(ApiException.class,
                () -> service.createExport(new CreateExportRequest("conversation", 600L,
                        new ExportProvenance(RESOURCE, "rev 2; drop table", "record_access")))).code());
    }

    @Test
    void heldConversationSurvivesRetentionAndExportKeepsProvenance() {
        as("auditor", ORG);
        service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        assertFalse(service.applyRetention(NOW).contains(RESOURCE),
                "a held conversation must survive the retention job");
        assertEquals("LEGAL_HOLD", service.retentionDecision(RESOURCE).reason());

        ExportProvenance provenance = new ExportProvenance(RESOURCE, "rev-2", "record_access");
        ExportRecord exported = service.createExport(
                new CreateExportRequest("conversation", 600L, provenance));
        assertEquals(provenance, exported.provenance());
        assertEquals(provenance, store.export(ORG, exported.id()).orElseThrow().provenance());
    }

    @Test
    void linkToChartPreservesProvenanceAndRequiresClinicalAuthorization() {
        as("clinician", ORG);
        ProvenanceLink link = service.linkToChart(new LinkToChartRequest(CONV, MSG,
                PATIENT, RESOURCE, "instruction", "instruction-rev-2", "rev-2"));
        assertEquals(MSG, link.messageId());
        assertEquals("instruction", link.sourceType());
        assertEquals("instruction-rev-2", link.sourceReference());
        assertEquals("rev-2", link.revision());
        assertEquals("clinician", link.linkedBy());
        assertEquals(NOW, link.linkedAt());
        assertEquals(List.of(link), store.provenanceLinks(ORG, MSG));
        assertEquals(AuditAction.CHART_LINK, lastAudit().action());

        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> service.linkToChart(new LinkToChartRequest(CONV, MSG, "bad-id",
                        RESOURCE, "message", "ref", "rev-1"))).code());

        as("auditor", ORG);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.linkToChart(new LinkToChartRequest(CONV, MSG, PATIENT,
                        RESOURCE, "message", "ref", "rev-1"))).status());
    }

    @Test
    void recordRevocationCapturesBlockingAndRecordAccessBasis() {
        as("admin", ORG);
        RevocationEffect effect = service.recordRevocation(CONNECTION);
        assertTrue(effect.messagingBlocked());
        assertTrue(effect.attachmentAccessBlocked());
        assertTrue(effect.priorConversationsReadable());
        assertEquals("policy-v1", effect.recordAccessBasis());
        assertEquals(CONNECTION, effect.connectionId());
        assertEquals(AuditAction.RESTRICT, lastAudit().action());

        assertEquals("CONNECTION_ACTIVE", assertThrows(ApiException.class,
                () -> service.recordRevocation(ACTIVE_CONNECTION)).code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.recordRevocation(MISSING)).status());

        as("patient-user", ORG);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.recordRevocation(CONNECTION)).status());
    }

    @Test
    void attachmentAccessFailsClosedAfterRevocation() {
        as("clinician", ORG);
        service.requireAttachmentAccess(ACTIVE_CONNECTION, RESOURCE);
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());
        assertEquals(AuditAction.DOWNLOAD, lastAudit().action());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.requireAttachmentAccess(CONNECTION, RESOURCE));
        assertEquals(403, denied.status());
        assertEquals("ATTACHMENT_ACCESS_REVOKED", denied.code());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireAttachmentAccess(MISSING, RESOURCE)).status());
    }

    @Test
    void copyRequestIsRoutedToRecordAccessAndAudited() {
        as("patient-user", ORG);
        CopyRequest request = service.requestCopy(new RequestCopyRequest(RESOURCE, "record_access"));
        assertEquals("ROUTED", request.status());
        assertEquals("patient-user", request.requestedBy());
        assertEquals("record_access", request.purpose());
        assertEquals(List.of(request), store.copyRequests(ORG));
        assertEquals(AuditAction.COPY_REQUEST, lastAudit().action());

        assertEquals("INVALID_PURPOSE", assertThrows(ApiException.class,
                () -> service.requestCopy(new RequestCopyRequest(RESOURCE, "  "))).code());
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, () -> service.auditTrail(null)).status());
    }

    @Test
    void crossTenantRecordsAreConcealed() {
        as("admin", OTHER_ORG);
        List<AuditEvent> foreignTrail = service.auditTrail(null);
        assertEquals(1, foreignTrail.size());
        assertEquals(AuditAction.AUDIT_READ, foreignTrail.get(0).action());
        assertEquals(OTHER_ORG, foreignTrail.get(0).organizationId());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.releaseHold(RESOURCE, "release")).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.retentionDecision(RESOURCE)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.recordRevocation(CONNECTION)).status());
        assertTrue(store.copyRequests(OTHER_ORG).isEmpty());
    }

    @Test
    void newMessagingIsRefusedThroughARevokedConnection() {
        as("clinician", ORG);
        service.requireMessagingAllowed(ACTIVE_CONNECTION, RESOURCE);
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());
        assertEquals(AuditAction.SEND, lastAudit().action());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(CONNECTION, RESOURCE));
        assertEquals(403, denied.status());
        assertEquals("MESSAGING_REVOKED", denied.code());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(MISSING, RESOURCE)).status());
    }

    @Test
    void retentionDutiesPreserveSenderAndRecipientWindows() {
        as("auditor", ORG);
        store.putRetentionDuty(ORG, new MessagingAuditContract.RetentionDuty(ORG, RESOURCE, 30, 90));
        MessagingAuditContract.RetentionDuty duty = service.retentionDuty(RESOURCE);
        assertEquals(30, duty.senderRetentionDays());
        assertEquals(90, duty.recipientRetentionDays());
        assertEquals(RESOURCE, duty.resourceId());
        assertEquals(ORG, duty.organizationId());
        assertEquals(404, assertThrows(ApiException.class, () -> service.retentionDuty(MISSING)).status());

        // A hold on the resource never waives either party's duty.
        service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        assertEquals(90, service.retentionDuty(RESOURCE).recipientRetentionDays());
    }

    @Test
    void retentionAndHoldAuditsNameTheAffectedResource() {
        as("auditor", ORG);
        RetentionDecision decision = service.retentionDecision(RESOURCE);
        assertTrue(decision.eligible());
        assertEquals(RESOURCE, lastAudit().resourceId());
        assertEquals(AuditAction.AUDIT_READ, lastAudit().action());
        assertTrue(store.auditEvents(ORG, RESOURCE).stream()
                .anyMatch(event -> event.action() == AuditAction.AUDIT_READ));

        List<String> eligible = service.applyRetention(NOW);
        assertEquals(List.of(RESOURCE, ELIGIBLE), eligible);
        assertEquals(ELIGIBLE, lastAudit().resourceId());
        assertEquals(AuditAction.RETENTION_APPLY, lastAudit().action());
    }

    @Test
    void holdsOnOtherResourcesAndReleasedHoldsDoNotBlockPlacementOrRetention() {
        as("auditor", ORG);
        // A hold on a different resource exercises the non-matching arm.
        store.putLegalHold(ORG, new LegalHold("hold-other", ORG, RECENT, "other dispute",
                "auditor", NOW, null, null, null));
        // A released hold on this resource exercises the inactive arm.
        LegalHold released = new LegalHold("hold-released", ORG, RESOURCE, "old dispute",
                "auditor", NOW, "auditor", NOW, "resolved");
        store.putLegalHold(ORG, released);
        // Neither the other-resource hold nor the released hold makes this
        // resource ineligible, so the decision is ELIGIBLE before a fresh hold.
        assertTrue(service.retentionDecision(RESOURCE).eligible());
        LegalHold fresh = service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        assertTrue(fresh.active());
        assertEquals("Synthetic dispute", fresh.reason());
        assertEquals("LEGAL_HOLD", service.retentionDecision(RESOURCE).reason());
    }

    @Test
    void releasedHoldPreservesPlacementReasonAndReasonIsAudited() {
        as("auditor", ORG);
        LegalHold placed = service.placeHold(new PlaceHoldRequest(RESOURCE, "Synthetic dispute"));
        LegalHold released = service.releaseHold(placed.id(), "Dispute resolved");
        assertEquals("Synthetic dispute", released.reason());
        assertEquals("Dispute resolved", released.releasedReason());
        assertEquals("auditor", released.releasedBy());
    }

    @Test
    void missingRecordAccessPolicyFailsClosed() {
        store.clearRecordAccessPolicy(ORG);
        as("admin", ORG);
        RevocationEffect effect = service.recordRevocation(CONNECTION);
        assertFalse(effect.priorConversationsReadable());
        assertEquals("UNKNOWN", effect.recordAccessBasis());
    }

    @Test
    void chartLinkRequiresValidSourceTypeAndVerifiedPatientBinding() {
        as("clinician", ORG);
        assertEquals("INVALID_SOURCETYPE", assertThrows(ApiException.class,
                () -> service.linkToChart(new LinkToChartRequest(CONV, MSG, PATIENT, RESOURCE,
                        "unknown", "ref", "rev-1"))).code());
        // A connection in another tenant must not bind this patient.
        store.addConnection(OTHER_ORG, CONNECTION, UNIT, "foreign-patient", null);
        String unlinkedPatient = "abcdef01-2345-6789-abcd-ef0123456789";
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkToChart(new LinkToChartRequest(CONV, MSG, unlinkedPatient, RESOURCE,
                        "message", "ref", "rev-1"))).status());
    }

    @Test
    void controllerRoutesDelegateWithoutChangingFields() {
        MessagingGovernanceService mocked = mock(MessagingGovernanceService.class);
        MessagingGovernanceController controller = new MessagingGovernanceController(mocked);

        List<AuditEvent> events = List.of();
        when(mocked.auditTrail(RESOURCE)).thenReturn(events);
        assertEquals(events, controller.audit(RESOURCE));
        verify(mocked).auditTrail(RESOURCE);

        RecordActionRequest actionRequest = new RecordActionRequest("view", RESOURCE, UNIT);
        AuditEvent event = new AuditEvent("event-1", ORG, "clinician", "MSG_GOVERNANCE",
                AuditAction.VIEW, RESOURCE, UNIT, "corr-1", Outcome.SUCCESS, NOW);
        when(mocked.recordAction(actionRequest)).thenReturn(event);
        assertEquals(event, controller.recordAction(actionRequest));
        verify(mocked).recordAction(actionRequest);

        PlaceHoldRequest holdRequest = new PlaceHoldRequest(RESOURCE, "dispute");
        LegalHold hold = new LegalHold("hold-1", ORG, RESOURCE, "dispute", "auditor", NOW, null, null, null);
        when(mocked.placeHold(holdRequest)).thenReturn(hold);
        ResponseEntity<LegalHold> holdResponse = controller.placeHold(holdRequest);
        assertEquals(HttpStatus.CREATED, holdResponse.getStatusCode());
        assertEquals(hold, holdResponse.getBody());
        assertEquals("/api/v1/messaging-governance/legal-holds/hold-1",
                holdResponse.getHeaders().getLocation().toString());

        when(mocked.releaseHold("hold-1", "resolved")).thenReturn(hold);
        assertEquals(hold, controller.releaseHold("hold-1", "resolved"));
        verify(mocked).releaseHold("hold-1", "resolved");

        RetentionDecision decision = new RetentionDecision(RESOURCE, true, "ELIGIBLE");
        when(mocked.retentionDecision(RESOURCE)).thenReturn(decision);
        assertEquals(decision, controller.retention(RESOURCE));
        verify(mocked).retentionDecision(RESOURCE);

        CreateExportRequest exportRequest = new CreateExportRequest("conversation", 600L, PROVENANCE);
        ExportRecord export = new ExportRecord("export-1", ORG, "conversation", "auditor", NOW,
                NOW.plusSeconds(600), PROVENANCE);
        when(mocked.createExport(exportRequest)).thenReturn(export);
        ResponseEntity<ExportRecord> exportResponse = controller.createExport(exportRequest);
        assertEquals(HttpStatus.CREATED, exportResponse.getStatusCode());
        assertEquals(export, exportResponse.getBody());

        LinkToChartRequest linkRequest = new LinkToChartRequest("conv-1", "msg-1", PATIENT,
                RESOURCE, "message", "ref", "rev-1");
        ProvenanceLink link = new ProvenanceLink("link-1", ORG, "conv-1", "msg-1", PATIENT,
                RESOURCE, "message", "ref", "rev-1", "clinician", NOW);
        when(mocked.linkToChart(linkRequest)).thenReturn(link);
        ResponseEntity<ProvenanceLink> linkResponse = controller.linkToChart(linkRequest);
        assertEquals(HttpStatus.CREATED, linkResponse.getStatusCode());
        assertEquals(link, linkResponse.getBody());

        RevocationEffect effect = new RevocationEffect("effect-1", ORG, CONNECTION, true, true,
                true, "policy-v1", NOW);
        when(mocked.recordRevocation(CONNECTION)).thenReturn(effect);
        assertEquals(effect, controller.recordRevocation(CONNECTION));
        verify(mocked).recordRevocation(CONNECTION);

        RequestCopyRequest copyRequest = new RequestCopyRequest(RESOURCE, "record_access");
        CopyRequest copy = new CopyRequest("copy-1", ORG, RESOURCE, "patient-user",
                "record_access", "ROUTED", NOW);
        when(mocked.requestCopy(copyRequest)).thenReturn(copy);
        assertNotNull(controller.requestCopy(copyRequest));
        assertEquals(copy, controller.requestCopy(copyRequest));
        verify(mocked, org.mockito.Mockito.times(2)).requestCopy(copyRequest);
    }
}
