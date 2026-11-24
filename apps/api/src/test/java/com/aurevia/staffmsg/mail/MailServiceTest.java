package com.aurevia.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.staff.StaffMember;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staff.StaffRepository.StaffRow;
import com.aurevia.staffmsg.directory.DirectoryPolicy;
import com.aurevia.staffmsg.directory.StaffDirectoryService;
import com.aurevia.tenancy.Facility;
import com.aurevia.tenant.TenantContext;
import com.aurevia.staffmsg.mail.MailCommands.CreateDraftCommand;
import com.aurevia.staffmsg.mail.MailCommands.FlagCommand;
import com.aurevia.staffmsg.mail.MailCommands.ForwardCommand;
import com.aurevia.staffmsg.mail.MailCommands.MoveCommand;
import com.aurevia.staffmsg.mail.MailCommands.ReplyCommand;
import com.aurevia.staffmsg.mail.MailCommands.SaveDraftCommand;
import com.aurevia.staffmsg.mail.MailCommands.SendCommand;
import com.aurevia.staffmsg.mail.MailContract.DraftView;
import com.aurevia.staffmsg.mail.MailContract.MailView;
import com.aurevia.staffmsg.mail.MailContract.Recipient;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;

/** SMSG-05 acceptance for the mail kernel, using only synthetic fixtures. */
class MailServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private MutableDirectory directory;
    private MailService service;

    @BeforeEach
    void setUp() {
        directory = new MutableDirectory();
        service = new MailService(Clock.fixed(NOW, ZoneOffset.UTC), directory, new InMemoryMailStore());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void draftVersionsAdvanceAndAStaleDraftNeverOverwrites() {
        DraftView first = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), null);
        assertEquals(1, first.version());

        DraftView second = service.saveDraft(new SaveDraftCommand(ORG, "nurse-a", first.id(), 1,
                "Version 2", "edited body", first.to(), first.cc(), null, first.attachmentDocumentIds()));
        assertEquals(2, second.version());
        assertEquals("Version 2", second.subject());

        ApiException stale = assertThrows(ApiException.class, () -> service.saveDraft(
                new SaveDraftCommand(ORG, "nurse-a", first.id(), 1, "Stale", "lost", List.of(), List.of(), List.of(), List.of())));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());

        SentMail published = send(second, "nurse-a", List.of("nurse-b"), "draft-version").message();
        assertEquals("Version 2", published.subject());
        assertEquals("edited body", published.body());
    }

    @Test
    void blindCopyAndMissingOrMismatchedConfirmationAreRefused() {
        assertEquals("BLIND_COPY_NOT_ALLOWED", assertThrows(ApiException.class, () -> service.createDraft(
                new CreateDraftCommand(ORG, FACILITY, "nurse-a", "Handover", "body",
                        List.of(Recipient.person("nurse-b")), List.of(), List.of("hidden"), List.of()))).code());

        DraftView draft = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of());
        assertEquals(428, assertThrows(ApiException.class, () -> service.send(new SendCommand(
                ORG, "nurse-a", draft.id(), null, "no-confirm"))).status());
        assertEquals("RECIPIENT_CONFIRMATION_MISMATCH", assertThrows(ApiException.class,
                () -> service.send(new SendCommand(ORG, "nurse-a", draft.id(), List.of("wrong"), "mismatch"))).code());

        MailView sent = service.send(new SendCommand(ORG, "nurse-a", draft.id(),
                List.of("nurse-b"), "confirmed"));
        assertEquals(MailFolder.SENT, sent.viewerFolder());
        assertEquals(List.of("nurse-b"), sent.message().resolvedRecipients());
        assertEquals(MailFolder.INBOX, service.view(ORG, "nurse-b", sent.message().id()).viewerFolder());
    }

    @Test
    void sendRequiresASubjectAndAtLeastOneRecipient() {
        DraftView blank = draft("nurse-a", "   ", List.of(Recipient.person("nurse-b")), List.of(), List.of(), null);
        assertEquals("SUBJECT_REQUIRED", assertThrows(ApiException.class,
                () -> service.send(new SendCommand(ORG, "nurse-a", blank.id(), List.of("nurse-b"), "blank"))).code());

        DraftView empty = draft("nurse-a", "Handover", List.of(), List.of(), List.of(), null);
        assertEquals("NO_RECIPIENT", assertThrows(ApiException.class,
                () -> service.send(new SendCommand(ORG, "nurse-a", empty.id(), List.of(), "empty"))).code());
    }

    @Test
    void replyAllReachesEveryOriginalParticipantExceptTheReplier() {
        DraftView original = draft("nurse-a", "Handover",
                List.of(Recipient.person("nurse-b"), Recipient.person("doctor-a")),
                List.of(Recipient.person("nurse-c")), List.of(), null);
        SentMail published = send(original, "nurse-a",
                List.of("nurse-b", "doctor-a", "nurse-c"), "original").message();

        DraftView all = service.reply(new ReplyCommand(ORG, "nurse-b", published.id(), true, "thanks"));
        assertEquals(List.of("nurse-a", "doctor-a", "nurse-c"), all.to().stream().map(Recipient::value).toList());
        assertEquals("Re: handover", all.subject());
        assertEquals(published.threadId(), all.threadId());

        DraftView single = service.reply(new ReplyCommand(ORG, "nurse-b", published.id(), false, "thanks"));
        assertEquals(List.of("nurse-a"), single.to().stream().map(Recipient::value).toList());

        SentMail reply = send(all, "nurse-b", List.of("nurse-a", "doctor-a", "nurse-c"), "reply-all").message();
        assertEquals(List.of("nurse-a", "doctor-a", "nurse-c"), reply.resolvedRecipients());
        assertEquals(2, service.listThreads(ORG, "nurse-b").get(0).messages().size());
    }

    @Test
    void forwardingKeepsAttachmentLinksAndTheThreadReference() {
        DraftView original = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of("doc-1", "doc-2"), null);
        SentMail published = send(original, "nurse-a", List.of("nurse-b"), "original").message();

        DraftView forward = service.forward(new ForwardCommand(ORG, "nurse-b", published.id(),
                List.of(Recipient.person("nurse-c")), List.of(), "please review"));
        assertEquals(List.of("doc-1", "doc-2"), forward.attachmentDocumentIds());
        assertEquals("Fwd: handover", forward.subject());
        assertEquals(published.threadId(), forward.threadId());

        SentMail forwarded = send(forward, "nurse-b", List.of("nurse-c"), "forward").message();
        assertEquals(List.of("doc-1", "doc-2"), forwarded.attachmentDocumentIds());
        assertEquals(published.threadId(), forwarded.threadId());
        assertEquals(1, service.listThreads(ORG, "nurse-a").size());
        assertEquals(2, service.listThreads(ORG, "nurse-b").get(0).messages().size());
    }

    @Test
    void foldersArePerPersonAndMovesOnlyAffectTheActor() {
        DraftView draft = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), null);
        SentMail published = send(draft, "nurse-a", List.of("nurse-b"), "folders").message();

        assertEquals(1, service.listFolder(ORG, "nurse-a", MailFolder.SENT).size());
        assertTrue(service.listFolder(ORG, "nurse-a", MailFolder.INBOX).isEmpty());
        assertEquals(1, service.listFolder(ORG, "nurse-b", MailFolder.INBOX).size());
        assertTrue(service.listFolder(ORG, "nurse-b", MailFolder.SENT).isEmpty());

        MailView moved = service.move(new MoveCommand(ORG, "nurse-a", published.id(), MailFolder.ARCHIVE));
        assertEquals(MailFolder.ARCHIVE, moved.viewerFolder());
        assertTrue(service.listFolder(ORG, "nurse-a", MailFolder.SENT).isEmpty());
        assertEquals(1, service.listFolder(ORG, "nurse-a", MailFolder.ARCHIVE).size());
        assertEquals(1, service.listFolder(ORG, "nurse-b", MailFolder.INBOX).size());

        assertEquals("INVALID_FOLDER", assertThrows(ApiException.class,
                () -> service.move(new MoveCommand(ORG, "nurse-a", published.id(), null))).code());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.move(new MoveCommand(ORG, "nurse-a", "missing", MailFolder.SENT))).status());
    }

    @Test
    void roleRecipientsExpandAtSendTimeAndTheResolvedHoldersAreRecorded() {
        directory.holders.put("nurse", List.of("nurse-b", "nurse-c"));
        DraftView first = draft("nurse-a", "Handover", List.of(Recipient.role("nurse")),
                List.of(), List.of(), null);
        SentMail firstSent = send(first, "nurse-a", List.of("nurse-b", "nurse-c"), "role-1").message();
        assertEquals(List.of("nurse-b", "nurse-c"), firstSent.resolvedRecipients());

        directory.holders.put("nurse", List.of("nurse-c", "nurse-d"));
        DraftView second = draft("nurse-a", "Update", List.of(Recipient.role("nurse")),
                List.of(), List.of(), null);
        SentMail secondSent = send(second, "nurse-a", List.of("nurse-c", "nurse-d"), "role-2").message();
        assertEquals(List.of("nurse-c", "nurse-d"), secondSent.resolvedRecipients());
        assertEquals(List.of("nurse-b", "nurse-c"), firstSent.resolvedRecipients());

        DraftView unit = draft("nurse-a", "Ward", List.of(Recipient.unitMailbox("ward-a")),
                List.of(), List.of(), null);
        assertEquals(List.of("ward-a"), send(unit, "nurse-a", List.of("ward-a"), "unit").message()
                .resolvedRecipients());
    }

    @Test
    void flagsCanBeAddedClearedAndAreSafeToRepeat() {
        DraftView draft = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), null);
        SentMail published = send(draft, "nurse-a", List.of("nurse-b"), "flags").message();

        MailView added = service.flag(new FlagCommand(ORG, "nurse-a", published.id(), "flagged", true));
        assertEquals(List.of("flagged"), added.message().flags());
        assertEquals(List.of("flagged"), service.flag(
                new FlagCommand(ORG, "nurse-a", published.id(), "flagged", true)).message().flags());
        assertTrue(service.flag(new FlagCommand(ORG, "nurse-a", published.id(), "flagged", false))
                .message().flags().isEmpty());
        assertTrue(service.flag(new FlagCommand(ORG, "nurse-a", published.id(), "flagged", false))
                .message().flags().isEmpty());

        assertEquals("INVALID_FLAG", assertThrows(ApiException.class,
                () -> service.flag(new FlagCommand(ORG, "nurse-a", published.id(), " ", true))).code());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.flag(new FlagCommand(ORG, "nurse-a", "missing", "flagged", true))).status());
    }

    @Test
    void aRetriedSendWithTheSameClientKeyPublishesOneMessage() {
        DraftView draft = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), null);
        MailView first = service.send(new SendCommand(ORG, "nurse-a", draft.id(),
                List.of("nurse-b"), "retry-key"));
        MailView replay = service.send(new SendCommand(ORG, "nurse-a", draft.id(),
                List.of("nurse-b"), "retry-key"));
        assertEquals(first.message().id(), replay.message().id());
        assertEquals(1, service.listFolder(ORG, "nurse-a", MailFolder.SENT).size());
    }

    @Test
    void draftsAndMessagesAreIsolatedByTenantAndParticipation() {
        DraftView draft = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), null);
        assertEquals(404, assertThrows(ApiException.class, () -> service.saveDraft(
                new SaveDraftCommand(ORG, "nurse-b", draft.id(), 1, "theft", "body", List.of(), List.of(), List.of(), List.of()))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.send(new SendCommand(
                ORG, "nurse-b", draft.id(), List.of("nurse-b"), "theft"))).status());

        SentMail published = send(draft, "nurse-a", List.of("nurse-b"), "isolation").message();
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.view(ORG, "nurse-c", published.id())).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.view(OTHER_ORG, "nurse-a", published.id())).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.view(ORG, "nurse-a", "missing")).status());
        assertTrue(service.listThreads(ORG, "nurse-c").isEmpty());
        assertTrue(service.listThreads(OTHER_ORG, "nurse-a").isEmpty());
    }

    @Test
    void invalidBoundaryInputsAreRejected() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.createDraft(
                new CreateDraftCommand(null, FACILITY, "nurse-a", "s", "b", List.of(), List.of(), List.of(), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.createDraft(
                new CreateDraftCommand(ORG, FACILITY, null, "s", "b", List.of(), List.of(), List.of(), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.createDraft(
                new CreateDraftCommand(ORG, null, "nurse-a", "s", "b", List.of(), List.of(), List.of(), List.of()))).status());

        assertEquals(422, assertThrows(ApiException.class, () -> service.saveDraft(
                new SaveDraftCommand(ORG, "nurse-a", "x", 0, "s", "b", List.of(), List.of(), List.of(), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.saveDraft(
                new SaveDraftCommand(null, "nurse-a", "x", 1, "s", "b", List.of(), List.of(), List.of(), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.saveDraft(
                new SaveDraftCommand(ORG, null, "x", 1, "s", "b", List.of(), List.of(), List.of(), List.of()))).status());

        assertEquals(422, assertThrows(ApiException.class, () -> service.send(
                new SendCommand(null, "nurse-a", "x", List.of(), "k"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.send(
                new SendCommand(ORG, null, "x", List.of(), "k"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.send(
                new SendCommand(ORG, "nurse-a", "x", List.of(), " "))).status());

        assertEquals(422, assertThrows(ApiException.class, () -> service.reply(
                new ReplyCommand(null, "nurse-a", "x", false, "b"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.reply(
                new ReplyCommand(ORG, null, "x", false, "b"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.forward(
                new ForwardCommand(null, "nurse-a", "x", List.of(), List.of(), "b"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.forward(
                new ForwardCommand(ORG, null, "x", List.of(), List.of(), "b"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.move(
                new MoveCommand(null, "nurse-a", "x", MailFolder.SENT))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.move(
                new MoveCommand(ORG, null, "x", MailFolder.SENT))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.flag(
                new FlagCommand(null, "nurse-a", "x", "f", true))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.flag(
                new FlagCommand(ORG, null, "x", "f", true))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.listFolder(null, "nurse-a", MailFolder.SENT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.listFolder(ORG, null, MailFolder.SENT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.listFolder(ORG, "nurse-a", null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.listThreads(null, "nurse-a")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.listThreads(ORG, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.view(null, "nurse-a", "x")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.view(ORG, null, "x")).status());
    }

    @Test
    void productionConstructorResolvesRolesThroughTheStaffRepository() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(new StaffRow(new StaffMember("nurse-b", "B", "active",
                        List.of("nurse")), "id-b")));
        StaffDirectoryService directoryService = mock(StaffDirectoryService.class);
        when(directoryService.findFacilityById(FACILITY))
                .thenReturn(Optional.of(new Facility(ORG, "FAC-A", "North Clinic", "Europe/London")));
        AccessRepository access = mock(AccessRepository.class);
        when(access.outsideAssignedFacilities("nurse-b", "FAC-A")).thenReturn(false);
        TenantContext.set(new TenantContext(ORG, "nurse-a", "MAIL", "corr-1"));

        MailService production = new MailService(Clock.fixed(NOW, ZoneOffset.UTC), staff,
                guardedTransactions(), directoryService, access, new DirectoryPolicy(),
                new InMemoryMailStore());
        assertNotNull(production);

        DraftView draft = production.createDraft(new CreateDraftCommand(ORG, FACILITY, "nurse-a",
                "Handover", "body", List.of(Recipient.role("nurse")), List.of(), List.of(), List.of()));
        SentMail sent = production.send(new SendCommand(ORG, "nurse-a", draft.id(),
                List.of("nurse-b"), "production")).message();
        assertEquals(List.of("nurse-b"), sent.resolvedRecipients());
    }

    @Test
    void anUnknownDraftIsNotFound() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.saveDraft(
                new SaveDraftCommand(ORG, "nurse-a", "missing", 1, "s", "b",
                        List.of(), List.of(), List.of(), List.of()))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.send(new SendCommand(
                ORG, "nurse-a", "missing", List.of("nurse-b"), "missing"))).status());
    }

    @Test
    void threadsAreGroupedByNormalizedSubjectAndReference() {
        DraftView original = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), null);
        SentMail sent = send(original, "nurse-a", List.of("nurse-b"), "thread-1").message();
        DraftView reply = service.reply(new ReplyCommand(ORG, "nurse-a", sent.id(), false, "note"));
        send(reply, "nurse-a", List.of("nurse-a"), "thread-2");

        List<?> threads = service.listThreads(ORG, "nurse-a");
        assertEquals(1, threads.size());
        assertEquals("handover", service.listThreads(ORG, "nurse-a").get(0).subject());
        assertEquals(sent.threadId(), service.listThreads(ORG, "nurse-a").get(0).threadId());

        DraftView other = draft("nurse-a", "Roster", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), null);
        send(other, "nurse-a", List.of("nurse-b"), "thread-3");
        assertEquals(2, service.listThreads(ORG, "nurse-a").size());
        assertNotEquals(service.listThreads(ORG, "nurse-a").get(0).threadId(),
                service.listThreads(ORG, "nurse-a").get(1).threadId());
    }

    @Test
    void savingADraftCannotIntroduceABlindCopy() {
        DraftView draft = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of());
        assertEquals("BLIND_COPY_NOT_ALLOWED", assertThrows(ApiException.class, () -> service.saveDraft(
                new SaveDraftCommand(ORG, "nurse-a", draft.id(), 1, "Handover", "body", draft.to(),
                        draft.cc(), List.of("hidden"), draft.attachmentDocumentIds()))).code());
    }

    @Test
    void flagsAreSharedMessageMetadataWhileFoldersStayPerPerson() {
        DraftView draft = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of());
        SentMail published = send(draft, "nurse-a", List.of("nurse-b"), "shared-flags").message();

        service.flag(new FlagCommand(ORG, "nurse-b", published.id(), "flagged", true));
        assertEquals(List.of("flagged"), service.view(ORG, "nurse-a", published.id()).message().flags());
        assertEquals(MailFolder.SENT, service.view(ORG, "nurse-a", published.id()).viewerFolder());
        assertEquals(MailFolder.INBOX, service.view(ORG, "nurse-b", published.id()).viewerFolder());
    }

    @Test
    void independentMessagesNeedBothSubjectAndReferenceToShareAThread() {
        DraftView first = draft("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of());
        send(first, "nurse-a", List.of("nurse-b"), "same-subject-1");
        DraftView second = draft("nurse-a", "Re: HANDOVER", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of());
        send(second, "nurse-a", List.of("nurse-b"), "same-subject-2");

        assertEquals(2, service.listThreads(ORG, "nurse-b").size());
    }

    private static TransactionTemplate guardedTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transactions;
    }

    private DraftView draft(String actor, String subject, List<Recipient> to, List<Recipient> cc,
                            List<String> attachments, List<String> bcc) {
        return service.createDraft(new CreateDraftCommand(ORG, FACILITY, actor, subject, "body",
                to, cc, bcc, attachments));
    }

    private MailView send(DraftView draft, String actor, List<String> confirmed, String key) {
        return service.send(new SendCommand(ORG, actor, draft.id(), confirmed, key));
    }

    /** A directory whose holders can change between compose and send. */
    private static final class MutableDirectory implements RecipientDirectory {
        private final Map<String, List<String>> holders = new HashMap<>();

        @Override
        public List<String> holders(String organizationId, String facilityId, String role) {
            return holders.getOrDefault(role, List.of());
        }
    }
}
