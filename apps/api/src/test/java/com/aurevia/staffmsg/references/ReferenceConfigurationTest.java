package com.aurevia.staffmsg.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aurevia.api.ApiException;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventService;
import com.aurevia.messaging.governance.MessagingGovernanceService;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.references.ReferenceContract.AccessOutcome;
import com.aurevia.staffmsg.references.ReferenceContract.AuditType;
import com.aurevia.staffmsg.references.ReferenceContract.NotificationDraft;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceAudit;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceKind;
import com.aurevia.tenant.TenantContext;

/** Production adapters that delegate the narrow ports to existing domain beans. */
class ReferenceConfigurationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "nurse-a";
    private static final String REFERENCE = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String TARGET = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private MessagingGovernanceService governance;
    private StaffRepository staff;
    private DurableEventService events;
    private ReferenceConfiguration configuration;

    /** Set a synthetic tenant so audited and published facts can be keyed. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "treatment", "synthetic-correlation"));
        governance = mock(MessagingGovernanceService.class);
        staff = mock(StaffRepository.class);
        events = mock(DurableEventService.class);
        configuration = new ReferenceConfiguration();
    }

    /** Clear the thread-local tenant after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** The bean methods expose the three adapters without any hidden collaborators. */
    @Test
    void beanMethodsExposeTheAdapters() {
        assertInstanceOf(ReferenceConfiguration.GovernanceAccess.class, configuration.referenceAccess(governance));
        assertInstanceOf(ReferenceConfiguration.StaffAuditAdapter.class, configuration.referenceAuditor(staff));
        assertInstanceOf(ReferenceConfiguration.OutboxNotifier.class, configuration.referenceNotifier(events));
    }

    /** A clean governance check means the recipient may open the reference. */
    @Test
    void governanceAccessAllowsWhenTheConnectionCheckPasses() {
        ReferenceAccess access = configuration.referenceAccess(governance);
        assertEquals(AccessOutcome.ALLOWED,
                access.check(ORG, SUBJECT, ReferenceKind.PATIENT, TARGET, CONNECTION));
        verify(governance).requireAttachmentAccess(CONNECTION, TARGET);
    }

    /** A missing connection is concealed as an unknown target. */
    @Test
    void governanceAccessMapsMissingConnectionToUnknownTarget() {
        org.mockito.Mockito.doThrow(new ApiException(404, "NOT_FOUND", "connection not found"))
                .when(governance).requireAttachmentAccess(CONNECTION, TARGET);
        ReferenceAccess access = configuration.referenceAccess(governance);
        assertEquals(AccessOutcome.UNKNOWN_TARGET,
                access.check(ORG, SUBJECT, ReferenceKind.PATIENT, TARGET, CONNECTION));
    }

    /** A revoked connection is reported as a revoked or stale reference. */
    @Test
    void governanceAccessMapsRevocationToRevokedOrStale() {
        org.mockito.Mockito.doThrow(new ApiException(403, "ATTACHMENT_ACCESS_REVOKED", "revoked"))
                .when(governance).requireAttachmentAccess(CONNECTION, TARGET);
        ReferenceAccess access = configuration.referenceAccess(governance);
        assertEquals(AccessOutcome.REVOKED_OR_STALE,
                access.check(ORG, SUBJECT, ReferenceKind.PATIENT, TARGET, CONNECTION));
    }

    /** A role or membership denial from governance is a missing care relationship. */
    @Test
    void governanceAccessMapsAuthorizationDenialToNoRelationship() {
        org.mockito.Mockito.doThrow(new ApiException(403, "FORBIDDEN", "missing permission"))
                .when(governance).requireAttachmentAccess(CONNECTION, TARGET);
        ReferenceAccess access = configuration.referenceAccess(governance);
        assertEquals(AccessOutcome.NO_RELATIONSHIP,
                access.check(ORG, SUBJECT, ReferenceKind.PATIENT, TARGET, CONNECTION));
    }

    /** Audit facts carry only the opaque reference id and fixed action/outcome strings. */
    @Test
    void auditorAppendsOnlyOpaqueFields() {
        ReferenceAuditor auditor = configuration.referenceAuditor(staff);
        auditor.append(new ReferenceAudit(REFERENCE, ORG, REFERENCE, AuditType.OPENED, SUBJECT,
                "success", Instant.parse("2026-11-23T09:00:00Z")));
        verify(staff).appendAudit(TenantContext.current(), "opened", REFERENCE, "success");
    }

    /** A missing tenant context fails closed rather than writing an unkeyed audit row. */
    @Test
    void auditorFailsClosedWithoutTenantContext() {
        TenantContext.clear();
        ReferenceAuditor auditor = configuration.referenceAuditor(staff);
        ApiException denied = assertThrows(ApiException.class, () ->
                auditor.append(new ReferenceAudit(REFERENCE, ORG, REFERENCE, AuditType.OPENED, SUBJECT,
                        "success", Instant.parse("2026-11-23T09:00:00Z"))));
        assertEquals(401, denied.status());
        assertEquals("UNAUTHENTICATED", denied.code());
    }

    /** The outbox carries only fixed text, a fixed namespace and an opaque aggregate id. */
    @Test
    void notifierPublishesAnIdentifierFreeDraft() {
        ReferenceNotifier notifier = configuration.referenceNotifier(events);
        NotificationDraft draft = new NotificationDraft("REFERENCE_OPENED", "A message reference was opened.",
                REFERENCE);
        notifier.dispatch(draft);
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(events).publish(captor.capture());
        PublishRequest request = captor.getValue();
        assertEquals(REFERENCE, request.aggregateId());
        assertEquals(1, request.aggregateVersion());
        assertEquals("REFERENCE_OPENED", request.eventType());
        assertEquals("staff-reference", request.namespace());
        assertEquals(64, request.payloadDigest().length());
        assertEquals(REFERENCE + ":REFERENCE_OPENED", request.idempotencyKey());
        assertFalse(request.eventType().contains(TARGET));
        assertFalse(request.namespace().contains(SUBJECT));
        assertFalse(request.payloadDigest().contains(TARGET));
        assertEquals(64, request.payloadDigest().length());
    }

    /** A denied publication propagates so an open never claims a notification it did not send. */
    @Test
    void notifierPropagatesAPublishFailure() {
        when(events.publish(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "publish denied"));
        ReferenceNotifier notifier = configuration.referenceNotifier(events);
        ApiException denied = assertThrows(ApiException.class, () ->
                notifier.dispatch(new NotificationDraft("REFERENCE_OPENED", "A message reference was opened.",
                        REFERENCE)));
        assertEquals(403, denied.status());
        assertTrue(denied.getMessage().contains("publish denied"));
    }
}
