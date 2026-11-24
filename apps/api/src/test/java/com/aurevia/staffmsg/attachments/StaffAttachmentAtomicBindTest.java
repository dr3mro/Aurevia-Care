package com.aurevia.staffmsg.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.ScanRequest;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.StaffActor;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.UploadRequest;

/**
 * Deterministic same-key race check for attachment publication (MSG-03, CLN-07).
 *
 * <p>Two publications observe the same pending file while only the first
 * conditional store write succeeds. The loser must not append a second SENT
 * fact. Concurrency is modelled with stubbed return values instead of threads so
 * the assertion cannot pass by scheduler luck.</p>
 */
class StaffAttachmentAtomicBindTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SENDER = "nurse-a";
    private static final String RECIPIENT = "clinician-b";
    private static final String DRAFT = "draft-1";
    private static final String MESSAGE = "message-1";
    private static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);

    private StaffAttachment pending;
    private StaffAttachmentStore store;
    private StaffAttachmentService service;
    private StaffActor sender;

    /** Build one clean pending file and a store that models the race window. */
    @BeforeEach
    void setUp() {
        InMemoryStaffAttachmentStore fixture = new InMemoryStaffAttachmentStore();
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        StaffAttachmentService fixtureService =
                new StaffAttachmentService(fixture, clock, new StaffAttachmentLimits());
        sender = StaffActor.staff(ORG, SENDER, "nurse");
        StaffAttachment created = fixtureService.upload(sender, new UploadRequest(DRAFT,
                "application/pdf", "note.pdf", PDF, StaffAttachmentInputs.checksum(PDF)));
        fixtureService.scan(StaffActor.staff(ORG, "scanner-a", "security_scanner"), created.id(),
                new ScanRequest("clean", "ok"));
        pending = fixture.attachment(ORG, created.id()).orElseThrow();

        store = mock(StaffAttachmentStore.class);
        when(store.attachment(anyString(), anyString())).thenReturn(Optional.of(pending));
        when(store.bindToMessage(any(StaffAttachment.class))).thenReturn(true, false);
        service = new StaffAttachmentService(store, clock, new StaffAttachmentLimits());
    }

    /** Only the caller whose conditional bind won records a SENT audit fact. */
    @Test
    void onlyTheWinningPublicationRecordsSent() {
        List<String> prepared = List.of(pending.id());

        service.publishPrepared(sender, MESSAGE, prepared, Set.of(RECIPIENT));
        service.publishPrepared(sender, MESSAGE, prepared, Set.of(RECIPIENT));

        verify(store, times(2)).bindToMessage(any(StaffAttachment.class));
        ArgumentCaptor<AttachmentAudit> audits = ArgumentCaptor.forClass(AttachmentAudit.class);
        verify(store, times(1)).appendAudit(audits.capture());
        assertEquals(AuditType.SENT, audits.getValue().type());
    }
}
