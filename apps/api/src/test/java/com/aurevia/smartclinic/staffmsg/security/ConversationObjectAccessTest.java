package com.aurevia.smartclinic.staffmsg.security;

import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.FACILITY_A;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.ORG_A;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.ORG_B;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.OUTSIDER;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.SENDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aurevia.smartclinic.staffmsg.instant.InstantConversationStore;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingController;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingService;
import com.aurevia.smartclinic.staffmsg.mail.MailController;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.SentMail;
import com.aurevia.smartclinic.staffmsg.mail.MailService;
import com.aurevia.smartclinic.staffmsg.mail.MailStore;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffParticipant;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Object-level access by guessed identifier on staff conversation and mail routes
 * (MSG-03, MSG-07).
 *
 * <p>The attacker never learns whether an identifier was ever issued: a foreign
 * existing id, a random id and a malformed non-blank selector all produce the same
 * 404 body on the same route when the store is tenant-scoped. Where the current
 * production code returns a different status for an existing object (a 403 for a
 * same-tenant non-participant conversation), the test states that behavior and the
 * residual concealment gap is recorded in the hand-off notes.</p>
 */
class ConversationObjectAccessTest {

    private static final String OWNED = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String FOREIGN = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String MAIL_OWNED = "12121212-1212-1212-1212-121212121212";
    private static final String MAIL_FOREIGN = "13131313-1313-1313-1313-131313131313";
    private static final String BODY = "synthetic object-level secret";
    private static final String MALFORMED = "not-a-uuid";

    private MockMvc instant;
    private MockMvc mail;

    /** Wire the routes over tenant-scoped stores with two distinct conversations. */
    @BeforeEach
    void setUp() {
        InstantConversationStore store = mock(InstantConversationStore.class);
        when(store.activeStaff(anyString(), anyString())).thenReturn(true);
        when(store.facilityWithinScope(anyString(), anyString(), anyString())).thenReturn(true);
        when(store.conversation(anyString(), anyString())).thenReturn(Optional.empty());
        when(store.conversation(ORG_A, OWNED)).thenReturn(Optional.of(conversation(OWNED)));
        when(store.conversation(ORG_A, FOREIGN)).thenReturn(Optional.of(conversation(FOREIGN)));
        Instant left = Instant.parse("2026-11-20T09:00:00Z");
        when(store.participants(anyString(), anyString())).thenReturn(List.of());
        when(store.participants(ORG_A, OWNED)).thenReturn(List.of(
                participant(OWNED, SENDER, null),
                participant(OWNED, StaffMessagingSecurityFixture.OTHER_SENDER, left)));
        when(store.participants(ORG_A, FOREIGN)).thenReturn(List.of(
                participant(FOREIGN, StaffMessagingSecurityFixture.OTHER_SENDER, null)));
        when(store.messages(anyString(), anyString(), anyLong(), any())).thenReturn(List.of());
        when(store.messages(eq(ORG_A), eq(OWNED), anyLong(), any())).thenReturn(List.of(
                new StaffMessage(ORG_A, "message-1", OWNED, 1L, SENDER, "nurse", BODY,
                        Instant.parse("2026-11-20T10:01:00Z"), null)));
        InstantMessagingService service = new InstantMessagingService(store,
                Clock.fixed(Instant.parse("2026-11-20T10:02:00Z"), ZoneOffset.UTC));
        instant = StaffMessagingSecurityFixture.mvc(new InstantMessagingController(service));

        MailStore mails = mock(MailStore.class);
        when(mails.message(anyString(), anyString())).thenReturn(Optional.empty());
        when(mails.message(ORG_A, MAIL_OWNED)).thenReturn(Optional.of(mail(MAIL_OWNED, SENDER)));
        when(mails.message(ORG_A, MAIL_FOREIGN)).thenReturn(Optional.of(mail(MAIL_FOREIGN,
                StaffMessagingSecurityFixture.OTHER_SENDER)));
        MailService mailService = new MailService(
                Clock.fixed(Instant.parse("2026-11-20T10:02:00Z"), ZoneOffset.UTC),
                (organizationId, facilityId, role) -> List.of(), mails);
        mail = StaffMessagingSecurityFixture.mvc(new MailController(mailService));
    }

    /** Clear the thread-local bound by each standalone request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static StaffConversation conversation(String id) {
        return new StaffConversation(ORG_A, FACILITY_A, id, ConversationKind.DIRECT, null, SENDER,
                Instant.parse("2026-11-20T10:00:00Z"));
    }

    private static StaffParticipant participant(String conversation, String subject, Instant leftAt) {
        return new StaffParticipant(ORG_A, conversation, subject, ParticipantRole.OWNER,
                Instant.parse("2026-11-20T09:00:00Z"), leftAt, false);
    }

    private static SentMail mail(String id, String sender) {
        return new SentMail(ORG_A, id, FACILITY_A, "thread-" + id, "Handover", BODY, sender, "nurse",
                Instant.parse("2026-11-20T10:01:00Z"), List.of(), List.of(),
                List.of(sender, StaffMessagingSecurityFixture.RECIPIENT), List.of(), List.of());
    }

    private MvcResult instantRead(Object id, String org, String subject) throws Exception {
        return instant.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/{id}/messages", id), org, subject)
                        .param("facilityId", FACILITY_A))
                .andReturn();
    }

    private MvcResult mailRead(Object id, String org, String subject) throws Exception {
        return mail.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/mail/messages/{id}", id), org, subject))
                .andReturn();
    }

    @Test
    void guessedConversationIdVariantsAreConcealedAcrossTenants() throws Exception {
        MvcResult existing = instantRead(OWNED, ORG_B, OUTSIDER);
        MvcResult random = instantRead(StaffMessagingSecurityFixture.randomId(), ORG_B, OUTSIDER);
        MvcResult malformed = instantRead(MALFORMED, ORG_B, OUTSIDER);
        assertEquals(404, existing.getResponse().getStatus());
        String expected = StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(random));
        assertEquals(expected, StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(existing)));
        assertEquals(expected, StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(malformed)));
        StaffMessagingSecurityFixture.assertNoLeak(StaffMessagingSecurityFixture.body(existing), BODY);
    }

    @Test
    void guessedMailIdVariantsAreConcealedAcrossTenants() throws Exception {
        MvcResult existing = mailRead(MAIL_OWNED, ORG_B, OUTSIDER);
        MvcResult random = mailRead(StaffMessagingSecurityFixture.randomId(), ORG_B, OUTSIDER);
        MvcResult malformed = mailRead(MALFORMED, ORG_B, OUTSIDER);
        assertEquals(404, existing.getResponse().getStatus());
        String expected = StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(random));
        assertEquals(expected, StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(existing)));
        assertEquals(expected, StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(malformed)));
        StaffMessagingSecurityFixture.assertNoLeak(StaffMessagingSecurityFixture.body(existing), BODY);
    }

    @Test
    void sameTenantNonParticipantConversationGetsTheCurrent403Behavior() throws Exception {
        // A guessed, existing same-tenant id returns 403 while a random id returns 404.
        // This status difference is a residual existence-leak gap recorded in Findings.
        MvcResult forbidden = instantRead(FOREIGN, ORG_A, SENDER);
        MvcResult absent = instantRead(StaffMessagingSecurityFixture.randomId(), ORG_A, SENDER);
        assertEquals(403, forbidden.getResponse().getStatus());
        assertEquals(404, absent.getResponse().getStatus());
        String body = StaffMessagingSecurityFixture.body(forbidden);
        assertTrue(body.contains("FORBIDDEN"));
        assertFalse(body.contains(BODY), "a refusal must not echo the private message body");
        StaffMessagingSecurityFixture.assertNoLeak(body, BODY);
    }

    @Test
    void sameTenantNonRecipientMailIdIsConcealed() throws Exception {
        MvcResult existing = mailRead(MAIL_FOREIGN, ORG_A, SENDER);
        MvcResult random = mailRead(StaffMessagingSecurityFixture.randomId(), ORG_A, SENDER);
        assertEquals(404, existing.getResponse().getStatus());
        assertEquals(StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(random)),
                StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(existing)));
        StaffMessagingSecurityFixture.assertNoLeak(StaffMessagingSecurityFixture.body(existing), BODY);
    }

    @Test
    void removedParticipantCannotSendButHistoryRemainsReadable() throws Exception {
        String leaver = StaffMessagingSecurityFixture.OTHER_SENDER;
        MvcResult history = instantRead(OWNED, ORG_A, leaver);
        assertEquals(200, history.getResponse().getStatus(),
                "a former member keeps the history they were entitled to see");
        assertTrue(StaffMessagingSecurityFixture.body(history).contains(BODY));

        String command = "{\"facilityId\":\"" + FACILITY_A + "\",\"conversationId\":\"" + OWNED
                + "\",\"body\":\"" + BODY + "\"}";
        instant.perform(StaffMessagingSecurityFixture.headers(
                        post("/api/v1/staff/instant/{id}/messages", OWNED), ORG_A, leaver)
                        .header("Idempotency-Key", "removed-participant-send")
                        .contentType(MediaType.APPLICATION_JSON).content(command))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(result -> StaffMessagingSecurityFixture.assertNoLeak(
                        StaffMessagingSecurityFixture.body(result), BODY));
    }

    @Test
    void replyToAGuessedMailIdIsRefusedWithoutPayload() throws Exception {
        String guessed = StaffMessagingSecurityFixture.randomId();
        mail.perform(StaffMessagingSecurityFixture.headers(
                        post("/api/v1/staff/mail/messages/{id}/reply", guessed), ORG_B, OUTSIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + BODY + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(result -> StaffMessagingSecurityFixture.assertNoLeak(
                        StaffMessagingSecurityFixture.body(result), BODY));
    }
}
