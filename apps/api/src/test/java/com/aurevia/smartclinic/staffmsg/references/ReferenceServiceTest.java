package com.aurevia.smartclinic.staffmsg.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.AccessOutcome;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.AttachRequest;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.AttachResult;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.AuditType;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.MessageReference;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.NotificationDraft;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.OpenResult;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceActor;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceAudit;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceHandle;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceKind;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceView;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.SubjectWarning;

import tools.jackson.databind.ObjectMapper;

/** Domain behavior of message references: label-only views, access-checked opens and clean notifications. */
class ReferenceServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "nurse-a";
    private static final String TARGET = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String LABEL = "Visit summary";
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeAccess access;
    private CapturingAuditor auditor;
    private CapturingNotifier notifier;
    private ReferenceService service;
    private ReferenceActor actor;

    /** Wire the service over synthetic ports and a fixed clock. */
    @BeforeEach
    void setUp() {
        access = new FakeAccess();
        auditor = new CapturingAuditor();
        notifier = new CapturingNotifier();
        service = new ReferenceService(access, auditor, notifier,
                Clock.fixed(Instant.parse("2026-11-23T09:00:00Z"), ZoneOffset.UTC));
        actor = ReferenceActor.staff(ORG, SUBJECT, "nurse");
    }

    private AttachResult attach(String subject) {
        return service.attach(actor, new AttachRequest("patient", TARGET, CONNECTION, LABEL, subject));
    }

    private ReferenceHandle handle(MessageReference reference) {
        return new ReferenceHandle(reference.id(), reference.connectionId(),
                reference.kind().wire(), reference.targetId(), reference.label());
    }

    /** Attaching yields an opaque reference and a warning that never repeats the subject. */
    @Test
    void attachReturnsReferenceAndSubjectWarningWithoutCopyingTheSubject() {
        String subject = "chart MRN: 42";
        AttachResult result = attach(subject);
        MessageReference reference = result.reference();
        assertEquals(ORG, reference.organizationId());
        assertEquals(ReferenceKind.PATIENT, reference.kind());
        assertEquals(TARGET, reference.targetId());
        assertEquals(LABEL, reference.label());
        assertTrue(result.warning().suspicious());
        assertEquals("MRN_LIKE", result.warning().reasonCode());
        assertFalse(result.warning().toString().contains(subject));
        assertEquals(1, auditor.audits.size());
        assertEquals(AuditType.ATTACHED, auditor.audits.get(0).type());
        assertEquals("success", auditor.audits.get(0).outcome());
        String json = JSON.writeValueAsString(reference);
        assertFalse(json.contains(subject), "the reference must not carry the subject line");
        assertFalse(json.contains("MRN: 42"));
    }

    /** The view exposes only the label and the opaque id, never a target identifier. */
    @Test
    void viewProjectsLabelOnly() {
        MessageReference reference = attach("follow up").reference();
        ReferenceView view = service.view(actor, handle(reference));
        assertEquals(reference.id(), view.id());
        assertEquals("patient", view.kind());
        assertEquals(LABEL, view.label());
        String json = JSON.writeValueAsString(view);
        assertTrue(json.contains(LABEL));
        assertFalse(json.contains("targetId"));
        assertFalse(json.contains(TARGET));
        assertFalse(json.contains(CONNECTION));
    }

    /** An access-checked open returns the target locator, one audit fact and a clean notification. */
    @Test
    void allowedOpenAuditsAndDispatchesIdentifierFreeNotification() throws Exception {
        MessageReference reference = attach("follow up").reference();
        auditor.audits.clear();
        access.outcome = AccessOutcome.ALLOWED;
        OpenResult opened = service.open(actor, handle(reference));
        assertEquals(reference.id(), opened.id());
        assertEquals(TARGET, opened.targetId());
        assertEquals(1, auditor.audits.size());
        assertEquals(AuditType.OPENED, auditor.audits.get(0).type());
        assertEquals(1, notifier.drafts.size());
        NotificationDraft draft = notifier.drafts.get(0);
        assertEquals("REFERENCE_OPENED", draft.eventType());
        assertEquals("A message reference was opened.", draft.text());
        String json = JSON.writeValueAsString(draft);
        assertFalse(json.contains(TARGET), "the notification must not carry the target identifier");
        assertFalse(json.contains(CONNECTION));
        assertFalse(json.contains(LABEL));
        assertFalse(json.contains("follow up"));
    }

    /** No care relationship: the open is blocked, audited and returns no target data. */
    @Test
    void openWithoutRelationshipIsBlocked() {
        MessageReference reference = attach("follow up").reference();
        auditor.audits.clear();
        access.outcome = AccessOutcome.NO_RELATIONSHIP;
        ApiException denied = assertThrows(ApiException.class, () -> service.open(actor, handle(reference)));
        assertEquals(403, denied.status());
        assertEquals("REFERENCE_BLOCKED", denied.code());
        assertFalse(denied.getMessage().contains(TARGET));
        assertFalse(denied.getMessage().contains(LABEL));
        assertEquals(1, auditor.audits.size());
        assertEquals(AuditType.ACCESS_DENIED, auditor.audits.get(0).type());
        assertEquals("blocked", auditor.audits.get(0).outcome());
        assertTrue(notifier.drafts.isEmpty());
    }

    /** A revoked or stale connection is blocked in the same way as a missing relationship. */
    @Test
    void openWithRevokedOrStaleTargetIsBlocked() {
        MessageReference reference = attach("follow up").reference();
        access.outcome = AccessOutcome.REVOKED_OR_STALE;
        ApiException denied = assertThrows(ApiException.class, () -> service.open(actor, handle(reference)));
        assertEquals(403, denied.status());
        assertEquals("REFERENCE_BLOCKED", denied.code());
        assertEquals(AuditType.ACCESS_DENIED, auditor.audits.get(auditor.audits.size() - 1).type());
    }

    /** An unknown target is concealed as a not-found, without exposing its existence. */
    @Test
    void openWithUnknownTargetIsConcealed() {
        MessageReference reference = attach("follow up").reference();
        access.outcome = AccessOutcome.UNKNOWN_TARGET;
        ApiException denied = assertThrows(ApiException.class, () -> service.open(actor, handle(reference)));
        assertEquals(404, denied.status());
        assertEquals("REFERENCE_NOT_FOUND", denied.code());
        assertFalse(denied.getMessage().contains(TARGET));
        assertEquals("not_found", auditor.audits.get(auditor.audits.size() - 1).outcome());
    }

    /** A wrong-tenant target is concealed identically to an unknown one. */
    @Test
    void openWithWrongTenantTargetIsConcealed() {
        MessageReference reference = attach("follow up").reference();
        access.outcome = AccessOutcome.WRONG_TENANT;
        ApiException denied = assertThrows(ApiException.class, () -> service.open(actor, handle(reference)));
        assertEquals(404, denied.status());
        assertEquals("REFERENCE_NOT_FOUND", denied.code());
        assertFalse(denied.getMessage().contains(TARGET));
        assertEquals("not_found", auditor.audits.get(auditor.audits.size() - 1).outcome());
    }

    /** An invalid wire kind is rejected before any side effect. */
    @Test
    void openWithInvalidKindIsRejected() {
        MessageReference reference = attach("follow up").reference();
        ReferenceHandle bad = new ReferenceHandle(reference.id(), reference.connectionId(),
                "billing", reference.targetId(), reference.label());
        ApiException denied = assertThrows(ApiException.class, () -> service.open(actor, bad));
        assertEquals(422, denied.status());
        assertEquals("INVALID_REFERENCE_KIND", denied.code());
        assertTrue(notifier.drafts.isEmpty());
    }

    /** The service exposes the screener for a sender-side pre-check. */
    @Test
    void screenSubjectDelegatesToThePureScreener() {
        SubjectWarning warning = service.screenSubject("nurse@example.test");
        assertTrue(warning.suspicious());
        assertEquals("EMAIL_LIKE", warning.reasonCode());
    }

    /** Synthetic access port whose outcome is set per test. */
    private static final class FakeAccess implements ReferenceAccess {
        private AccessOutcome outcome = AccessOutcome.ALLOWED;

        @Override
        public AccessOutcome check(String organizationId, String subject, ReferenceKind kind,
                                   String targetId, String connectionId) {
            return outcome;
        }
    }

    /** Captures audit facts so the tests can assert exactly one fact per operation. */
    private static final class CapturingAuditor implements ReferenceAuditor {
        private final List<ReferenceAudit> audits = new ArrayList<>();

        @Override
        public void append(ReferenceAudit audit) {
            audits.add(audit);
        }
    }

    /** Captures notification drafts so the tests can assert they carry no identifiers. */
    private static final class CapturingNotifier implements ReferenceNotifier {
        private final List<NotificationDraft> drafts = new ArrayList<>();

        @Override
        public void dispatch(NotificationDraft draft) {
            drafts.add(draft);
        }
    }
}
