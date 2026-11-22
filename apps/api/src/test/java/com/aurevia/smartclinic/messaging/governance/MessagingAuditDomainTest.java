package com.aurevia.smartclinic.messaging.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ConnectionReference;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CreateExportRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LinkToChartRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.MessagingResource;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RecordAccessPolicy;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RecordActionRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RequestCopyRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;

/**
 * Unit acceptance for the pure MSG-07 governance vocabulary, validation and
 * tenant-scoped synthetic store.
 *
 * <p>All fixtures use synthetic identifiers. No patient data or credentials are
 * present.</p>
 */
class MessagingAuditDomainTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String RESOURCE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNIT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CONNECTION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final ExportProvenance PROVENANCE =
            new ExportProvenance(RESOURCE, "rev-2", "record_access");

    @Test
    void everyAuditActionRoundTripsItsWireValue() {
        for (AuditAction action : AuditAction.values()) {
            assertEquals(action, AuditAction.from(action.wire()));
        }
        assertEquals(16, AuditAction.values().length);
        assertEquals("open", AuditAction.OPEN.wire());
        assertEquals("acknowledge", AuditAction.ACKNOWLEDGE.wire());
        assertEquals("restrict", AuditAction.RESTRICT.wire());
        assertEquals("retention_apply", AuditAction.RETENTION_APPLY.wire());
    }

    @Test
    void unknownAuditActionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditAction.from("demographics"));
        assertTrue(error.getMessage().contains("audit action"));
    }

    @Test
    void outcomesRoundTripAndRejectUnknownValues() {
        for (Outcome outcome : Outcome.values()) {
            assertEquals(outcome, Outcome.from(outcome.wire()));
        }
        assertEquals(4, Outcome.values().length);
        assertEquals("success", Outcome.SUCCESS.wire());
        assertEquals("not_found", Outcome.NOT_FOUND.wire());
        assertThrows(IllegalArgumentException.class, () -> Outcome.from("maybe"));
    }

    @Test
    void legalHoldTracksActiveStateFromReleaseInstant() {
        LegalHold active = new LegalHold("hold-1", ORG, RESOURCE, "dispute", "auditor", NOW, null, null, null);
        LegalHold released = new LegalHold("hold-2", ORG, RESOURCE, "dispute", "auditor", NOW,
                "auditor", NOW.plusSeconds(60), "resolved");
        assertTrue(active.active());
        assertFalse(released.active());
        assertEquals(RESOURCE, active.resourceId());
        assertEquals("dispute", active.reason());
        assertEquals("auditor", active.placedBy());
        assertEquals(NOW, active.placedAt());
        assertEquals("auditor", released.releasedBy());
        assertEquals(NOW.plusSeconds(60), released.releasedAt());
        assertNotEquals(active, released);
    }

    @Test
    void auditEventCarriesOnlyNonSensitiveGovernanceFacts() {
        AuditEvent event = new AuditEvent("event-1", ORG, "auditor", "MSG_GOVERNANCE",
                AuditAction.VIEW, RESOURCE, UNIT, "corr-1", Outcome.SUCCESS, NOW);
        assertEquals("event-1", event.id());
        assertEquals(ORG, event.organizationId());
        assertEquals("auditor", event.subject());
        assertEquals("MSG_GOVERNANCE", event.purpose());
        assertEquals(AuditAction.VIEW, event.action());
        assertEquals(RESOURCE, event.resourceId());
        assertEquals(UNIT, event.unitId());
        assertEquals("corr-1", event.correlationId());
        assertEquals(Outcome.SUCCESS, event.outcome());
        assertEquals(NOW, event.occurredAt());
        for (RecordComponent component : AuditEvent.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(List.of("text", "content", "body", "payload", "diagnosis", "note").contains(name),
                    "audit must not carry clinical payload fields: " + name);
        }
    }

    @Test
    void governanceRecordsExposeTheirImmutableFacts() {
        RetentionPolicy policy = new RetentionPolicy(ORG, "conversation", 30);
        MessagingResource resource = new MessagingResource(RESOURCE, ORG, "conversation", NOW);
        RecordAccessPolicy access = new RecordAccessPolicy(ORG, true, "policy-v1");
        ProvenanceLink link = new ProvenanceLink("link-1", ORG, "conv-1", "msg-1", "patient-1",
                "chart-1", "instruction", "instruction-1", "rev-2", "clinician", NOW);
        ExportRecord export = new ExportRecord("export-1", ORG, "conversation", "auditor", NOW,
                NOW.plusSeconds(60), PROVENANCE);
        CopyRequest copy = new CopyRequest("copy-1", ORG, RESOURCE, "patient-user", "record_access",
                "ROUTED", NOW);
        RevocationEffect effect = new RevocationEffect("effect-1", ORG, CONNECTION, true, true,
                true, "policy-v1", NOW);
        RetentionDecision decision = new RetentionDecision(RESOURCE, true, "ELIGIBLE");

        assertEquals(30, policy.retentionDays());
        assertEquals("conversation", policy.resourceType());
        assertEquals(ORG, resource.organizationId());
        assertEquals(NOW, resource.createdAt());
        assertTrue(access.priorConversationsReadable());
        assertEquals("policy-v1", access.policyVersion());
        assertEquals("msg-1", link.messageId());
        assertEquals("rev-2", link.revision());
        assertEquals("clinician", link.linkedBy());
        assertEquals("instruction", link.sourceType());
        assertEquals("auditor", export.createdBy());
        assertEquals(PROVENANCE, export.provenance());
        assertEquals("rev-2", export.provenance().sourceRevision());
        assertTrue(export.expiresAt().isAfter(NOW));
        assertEquals("ROUTED", copy.status());
        assertEquals("record_access", copy.purpose());
        assertTrue(effect.messagingBlocked());
        assertTrue(effect.attachmentAccessBlocked());
        assertTrue(effect.priorConversationsReadable());
        assertTrue(decision.eligible());
        assertEquals("ELIGIBLE", decision.reason());
    }

    @Test
    void connectionReferenceRecordsRevocationInstant() {
        ConnectionReference active = new ConnectionReference(CONNECTION, ORG, UNIT, "patient-1", null);
        ConnectionReference revoked = new ConnectionReference(CONNECTION, ORG, UNIT, "patient-1", NOW);
        assertNull(active.revokedAt());
        assertEquals(NOW, revoked.revokedAt());
        assertEquals(UNIT, active.unitId());
        assertEquals("patient-1", active.patientId());
    }

    @Test
    void commandsCarryOnlyStableNonSensitiveFields() {
        assertEquals("view", new RecordActionRequest("view", RESOURCE, UNIT).action());
        assertEquals(RESOURCE, new PlaceHoldRequest(RESOURCE, "dispute").resourceId());
        assertEquals("msg-1", new LinkToChartRequest("conv-1", "msg-1", "patient-1", "chart-1",
                "message", "ref-1", "rev-1").messageId());
        assertEquals(Long.valueOf(600),
                new CreateExportRequest("conversation", 600L, PROVENANCE).ttlSeconds());
        assertEquals(PROVENANCE,
                new CreateExportRequest("conversation", 600L, PROVENANCE).provenance());
        assertEquals("record_access", new RequestCopyRequest(RESOURCE, "record_access").purpose());
    }

    @Test
    void everyActionIsAllowedForSomeRoleAndDeniedForAnother() {
        MessagingGovernanceActions actions = new MessagingGovernanceActions();
        String[] roles = {"patient", "clinician", "unit_staff", "tenant_admin", "privacy_auditor", "unknown"};
        for (AuditAction action : AuditAction.values()) {
            boolean anyAllowed = false;
            boolean anyDenied = false;
            for (String role : roles) {
                boolean allowed = actions.allows(action, role);
                anyAllowed |= allowed;
                anyDenied |= !allowed;
            }
            assertTrue(anyAllowed, action + " must have an allowed role");
            assertTrue(anyDenied, action + " must have a denied role");
        }
        assertTrue(actions.allows(AuditAction.AUDIT_READ, "privacy_auditor"));
        assertFalse(actions.allows(AuditAction.AUDIT_READ, "patient"));
        assertTrue(actions.allows(AuditAction.CHART_LINK, "clinician"));
        assertFalse(actions.allows(AuditAction.CHART_LINK, "privacy_auditor"));
        assertTrue(actions.allows(AuditAction.RESTRICT, "tenant_admin"));
        assertFalse(actions.allows(AuditAction.RESTRICT, "clinician"));
        assertTrue(actions.allows(AuditAction.SEND, "patient"));
        assertTrue(actions.allows(AuditAction.DOWNLOAD, "unit_staff"));
        assertTrue(actions.allows(AuditAction.COPY_REQUEST, "patient"));
    }

    @Test
    void uuidValidationRejectsMalformedIdentifiers() {
        assertEquals(RESOURCE, MessagingGovernanceInputs.uuid(RESOURCE, "resourceId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.uuid("not-a-uuid", "resourceId")).status());
        assertEquals("INVALID_RESOURCEID", assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.uuid(null, "resourceId")).code());
    }

    @Test
    void textValidationNormalizesAndBoundsLength() {
        assertEquals("Synthetic dispute", MessagingGovernanceInputs.text("  Synthetic dispute  ", "reason", 64));
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.text("   ", "reason", 64)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.text(null, "reason", 64)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.text("x".repeat(65), "reason", 64)).status());
    }

    @Test
    void actionAndOutcomeValidationMapUnknownValuesToUnprocessable() {
        assertEquals(AuditAction.FILE, MessagingGovernanceInputs.action("file"));
        ApiException actionError = assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.action("guess"));
        assertEquals(422, actionError.status());
        assertEquals("INVALID_ACTION", actionError.code());
        assertEquals(Outcome.DENIED, MessagingGovernanceInputs.outcome("denied"));
        assertEquals("INVALID_OUTCOME", assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.outcome("unsure")).code());
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.action(null)).code());
        assertEquals("INVALID_OUTCOME", assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.outcome(null)).code());
    }

    @Test
    void workflowActionSeparatesRecordableActionsFromGovernanceCommands() {
        List<AuditAction> recordableActions = List.of(AuditAction.OPEN, AuditAction.VIEW,
                AuditAction.SEND, AuditAction.ASSIGN, AuditAction.DOWNLOAD, AuditAction.ACKNOWLEDGE,
                AuditAction.FILE, AuditAction.CORRECT, AuditAction.RESTRICT);
        for (AuditAction action : AuditAction.values()) {
            if (recordableActions.contains(action)) {
                assertEquals(action, MessagingGovernanceInputs.workflowAction(action.wire()));
            } else {
                assertEquals("INVALID_ACTION", assertThrows(ApiException.class,
                        () -> MessagingGovernanceInputs.workflowAction(action.wire())).code());
            }
        }
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.workflowAction("guess")).code());
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class,
                () -> MessagingGovernanceInputs.workflowAction(null)).code());
    }

    @Test
    void syntheticStoreKeepsEveryGovernanceSurfaceTenantScoped() {
        InMemoryMessagingGovernanceStore store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        store.addMember(OTHER_ORG, "auditor", "tenant_admin", true);
        store.addResource(ORG, RESOURCE, "conversation", NOW);
        store.addResource(OTHER_ORG, RESOURCE, "conversation", NOW);
        store.putRetentionPolicy(ORG, "conversation", 30);
        store.putRetentionPolicy(OTHER_ORG, "conversation", 60);
        store.addRecordAccessPolicy(ORG, true, "policy-v1");
        store.addRecordAccessPolicy(OTHER_ORG, false, "policy-v2");
        store.addConnection(ORG, CONNECTION, UNIT, "patient-1", NOW);
        store.addConnection(OTHER_ORG, CONNECTION, UNIT, "patient-1", null);

        store.appendAudit(new AuditEvent("event-1", ORG, "auditor", "MSG_GOVERNANCE",
                AuditAction.VIEW, RESOURCE, UNIT, "corr-1", Outcome.SUCCESS, NOW));
        store.appendAudit(new AuditEvent("event-2", OTHER_ORG, "auditor", "MSG_GOVERNANCE",
                AuditAction.VIEW, RESOURCE, UNIT, "corr-2", Outcome.SUCCESS, NOW));
        LegalHold hold = new LegalHold("hold-1", ORG, RESOURCE, "dispute", "auditor", NOW, null, null, null);
        LegalHold foreignHold = new LegalHold("hold-1", OTHER_ORG, RESOURCE, "hold", "auditor", NOW, null, null, null);
        store.putLegalHold(ORG, hold);
        store.putLegalHold(OTHER_ORG, foreignHold);
        ProvenanceLink link = new ProvenanceLink("link-1", ORG, "conv-1", "msg-1", "patient-1",
                "chart-1", "message", "ref-1", "rev-1", "clinician", NOW);
        ProvenanceLink foreignLink = new ProvenanceLink("link-1", OTHER_ORG, "conv-1", "msg-1",
                "patient-1", "chart-1", "message", "ref-1", "rev-1", "clinician", NOW);
        store.putProvenanceLink(ORG, link);
        store.putProvenanceLink(OTHER_ORG, foreignLink);
        ExportRecord export = new ExportRecord("export-1", ORG, "conversation", "auditor", NOW,
                NOW.plusSeconds(60), PROVENANCE);
        store.putExport(ORG, export);
        CopyRequest copy = new CopyRequest("copy-1", ORG, RESOURCE, "patient-user", "record_access",
                "ROUTED", NOW);
        store.putCopyRequest(ORG, copy);
        RevocationEffect effect = new RevocationEffect("effect-1", ORG, CONNECTION, true, true,
                true, "policy-v1", NOW);
        store.putRevocationEffect(ORG, effect);

        store.addMember(ORG, "inactive-auditor", "privacy_auditor", false);
        assertTrue(store.hasActiveMembership(ORG, "auditor"));
        assertTrue(store.hasActiveMembership(OTHER_ORG, "auditor"));
        assertFalse(store.hasActiveMembership(ORG, "unknown"));
        assertEquals(List.of("privacy_auditor"), store.activeRoles(ORG, "auditor"));
        assertEquals(List.of("tenant_admin"), store.activeRoles(OTHER_ORG, "auditor"));
        assertEquals(List.of(), store.activeRoles(ORG, "unknown"));
        assertEquals(List.of(), store.activeRoles(ORG, "inactive-auditor"));
        assertEquals(1, store.auditEvents(ORG).size());
        assertEquals(1, store.auditEvents(OTHER_ORG).size());
        assertEquals(1, store.auditEvents(ORG, RESOURCE).size());
        assertEquals(0, store.auditEvents(ORG, UNIT).size());
        assertEquals(30, store.retentionPolicy(ORG, "conversation").orElseThrow().retentionDays());
        assertEquals(60, store.retentionPolicy(OTHER_ORG, "conversation").orElseThrow().retentionDays());
        assertTrue(store.retentionPolicy(ORG, "unknown").isEmpty());
        assertEquals(1, store.resources(ORG).size());
        assertEquals("conversation", store.resources(ORG).get(0).resourceType());
        assertEquals(1, store.resources(OTHER_ORG).size());
        assertEquals("dispute", store.legalHold(ORG, "hold-1").orElseThrow().reason());
        assertEquals("hold", store.legalHold(OTHER_ORG, "hold-1").orElseThrow().reason());
        assertTrue(store.legalHold(ORG, "missing").isEmpty());
        assertEquals(1, store.legalHolds(ORG).size());
        assertEquals(1, store.legalHolds(OTHER_ORG).size());
        assertTrue(store.recordAccessPolicy(ORG).priorConversationsReadable());
        assertFalse(store.recordAccessPolicy(OTHER_ORG).priorConversationsReadable());
        assertEquals(NOW, store.connection(ORG, CONNECTION).orElseThrow().revokedAt());
        assertNull(store.connection(OTHER_ORG, CONNECTION).orElseThrow().revokedAt());
        assertTrue(store.connection(ORG, "missing").isEmpty());
        assertEquals(List.of(link), store.provenanceLinks(ORG, "msg-1"));
        assertEquals(List.of(), store.provenanceLinks(ORG, "missing-msg"));
        assertEquals(List.of(foreignLink), store.provenanceLinks(OTHER_ORG, "msg-1"));
        assertEquals(export, store.export(ORG, "export-1").orElseThrow());
        assertTrue(store.export(ORG, "missing").isEmpty());
        assertEquals(List.of(copy), store.copyRequests(ORG));
        assertEquals(List.of(), store.copyRequests(OTHER_ORG));
        assertEquals(List.of(effect), store.revocationEffects(ORG, CONNECTION));
        assertEquals(List.of(), store.revocationEffects(ORG, "missing"));
        assertEquals(List.of(), store.revocationEffects(OTHER_ORG, CONNECTION));
        assertTrue(MessagingGovernanceInputs.newId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
}
