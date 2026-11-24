package com.aurevia.smartclinic.staffmsg.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.ScanRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.UploadRequest;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Unit acceptance for the publication seam used by the real message sends
 * (MSG-03, CLN-07): fresh roles, no silent publication for an empty draft, and
 * the existing attachment policy for files that are ready.
 */
class StaffAttachmentPublicationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SENDER = "nurse-a";
    private static final String RECIPIENT = "clinician-b";
    private static final String STRANGER = "nurse-c";
    private static final String DRAFT = "conversation-1";
    private static final String MESSAGE = "message-1";
    private static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);

    private InMemoryStaffAttachmentStore store;
    private StaffRepository staff;
    private StaffAttachmentService service;
    private StaffAttachmentPublicationService publisher;

    /** Wire the publisher over a synthetic store and fresh membership source. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SENDER, "STAFF_MESSAGING", "correlation-1"));
        store = new InMemoryStaffAttachmentStore();
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SENDER)).thenReturn(List.of("nurse"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        service = new StaffAttachmentService(store, clock, new StaffAttachmentLimits());
        publisher = new StaffAttachmentPublicationService(service, staff, transactions);
    }

    /** Clear the thread-local tenant used by upload and scan. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String cleanFile() {
        var created = service.upload(
                com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffActor
                        .staff(ORG, SENDER, "nurse"),
                new UploadRequest(DRAFT, "application/pdf", "note.pdf", PDF,
                        StaffAttachmentInputs.checksum(PDF)));
        service.scan(com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffActor
                        .staff(ORG, "scanner-a", "security_scanner"),
                created.id(), new ScanRequest("clean", "ok"));
        return created.id();
    }

    /** The inert publication used by legacy callers performs no audit and no send. */
    @Test
    void inertPublicationDoesNothing() {
        StaffAttachmentPublication none = StaffAttachmentPublication.none();
        assertSame(none, StaffAttachmentPublication.none());
        none.requirePublishable(ORG, SENDER, DRAFT);
        none.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), List.of());
        assertTrue(store.audits(ORG).isEmpty());
    }

    /** An empty draft produces no attachment send and no audit fact. */
    @Test
    void emptyDraftIsNotPublished() {
        List<String> prepared = publisher.requirePublishable(ORG, SENDER, DRAFT);
        assertTrue(prepared.isEmpty());
        publisher.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), prepared);
        assertTrue(store.audits(ORG).isEmpty());
    }

    /** A clean file is bound to the committed message and only the passed audience is granted. */
    @Test
    void cleanFileIsPublishedUnderTheRealMessageIdWithThePassedAudience() {
        String id = cleanFile();
        List<String> prepared = publisher.requirePublishable(ORG, SENDER, DRAFT);
        assertEquals(List.of(id), prepared);
        publisher.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), prepared);
        assertTrue(store.attachment(ORG, id).orElseThrow().sent());
        assertEquals(MESSAGE, store.attachment(ORG, id).orElseThrow().messageId());
        assertTrue(store.isRecipient(ORG, MESSAGE, RECIPIENT));
        assertFalse(store.isRecipient(ORG, MESSAGE, STRANGER));
        assertEquals(1L, store.audits(ORG).stream()
                .filter(fact -> fact.type() == AuditType.SENT && "success".equals(fact.outcome()))
                .count());
    }

    /** A repeated publication of the same message issues no second SENT fact. */
    @Test
    void repeatedPublicationOfTheSameMessageIsIdempotent() {
        String id = cleanFile();
        List<String> prepared = publisher.requirePublishable(ORG, SENDER, DRAFT);
        publisher.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), prepared);
        publisher.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), prepared);
        assertEquals(id, prepared.get(0));
        assertEquals(1L, store.audits(ORG).stream()
                .filter(fact -> fact.type() == AuditType.SENT && "success".equals(fact.outcome()))
                .count());
    }

    /** A file already bound to another message cannot be rebound by a publication retry. */
    @Test
    void fileAlreadySentToAnotherMessageIsRefused() {
        String id = cleanFile();
        List<String> prepared = publisher.requirePublishable(ORG, SENDER, DRAFT);
        publisher.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), prepared);
        ApiException refused = assertThrows(ApiException.class,
                () -> publisher.publish(ORG, SENDER, DRAFT, "other-message", Set.of(RECIPIENT), prepared));
        assertEquals(409, refused.status());
        assertEquals(MESSAGE, store.attachment(ORG, id).orElseThrow().messageId());
    }

    /** A file that becomes unclean after preflight is refused rather than bound. */
    @Test
    void fileChangedAfterPreflightIsRefused() {
        String id = cleanFile();
        List<String> prepared = publisher.requirePublishable(ORG, SENDER, DRAFT);
        var original = store.attachment(ORG, id).orElseThrow();
        store.putAttachment(new com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract
                .StaffAttachment(ORG, id, SENDER, DRAFT, original.documentId(), original.fileName(),
                original.contentType(), original.sizeBytes(), original.checksum(), original.objectKey(),
                original.objectVersion(), com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract
                        .ScanState.QUARANTINED, null, false, original.createdAt()));
        ApiException refused = assertThrows(ApiException.class,
                () -> publisher.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), prepared));
        assertEquals(409, refused.status());
        assertFalse(store.attachment(ORG, id).orElseThrow().sent());
    }

    /** A file whose uploader changed between preflight and publish is refused. */
    @Test
    void foreignUploaderAfterPreflightIsRefused() {
        String id = cleanFile();
        List<String> prepared = publisher.requirePublishable(ORG, SENDER, DRAFT);
        var original = store.attachment(ORG, id).orElseThrow();
        store.putAttachment(new com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract
                .StaffAttachment(ORG, id, "nurse-c", DRAFT, original.documentId(), original.fileName(),
                original.contentType(), original.sizeBytes(), original.checksum(), original.objectKey(),
                original.objectVersion(), original.scanState(), null, false, original.createdAt()));
        ApiException refused = assertThrows(ApiException.class,
                () -> publisher.publish(ORG, SENDER, DRAFT, MESSAGE, Set.of(RECIPIENT), prepared));
        assertEquals(409, refused.status());
    }

    /** A sender without an active send role cannot publish, and the refusal is audited. */
    @Test
    void senderWithoutSendRoleIsDenied() {
        when(staff.activeRoles(SENDER)).thenReturn(List.of());
        ApiException denied = assertThrows(ApiException.class,
                () -> publisher.requirePublishable(ORG, SENDER, DRAFT));
        assertEquals(403, denied.status());
        assertTrue(store.audits(ORG).stream().anyMatch(fact -> fact.type() == AuditType.ACCESS_DENIED
                && "denied".equals(fact.outcome()) && fact.detail() != null
                && fact.detail().startsWith("STAFF_ATTACHMENT_")));
    }
}
