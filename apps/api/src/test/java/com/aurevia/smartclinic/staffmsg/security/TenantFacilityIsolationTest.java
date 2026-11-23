package com.aurevia.smartclinic.staffmsg.security;

import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.FACILITY_A;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.FACILITY_B;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.GHOST;
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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * Cross-tenant and cross-facility refusals on the instant-messaging and mail HTTP
 * entry points (MSG-03, MSG-07), proved through the real controllers and services.
 *
 * <p>A foreign tenant's identifier and a never-issued identifier must produce the
 * same concealed 404 body on the same route. A same-tenant wrong-facility request
 * is refused by the facility guard. Every fixture value is synthetic.</p>
 */
class TenantFacilityIsolationTest {

    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String MESSAGE = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String BODY = "synthetic private handover";

    private MockMvc instant;
    private MockMvc mail;
    private AtomicBoolean activeStaff;

    /** Wire both controllers over tenant-scoped synthetic boundaries. */
    @BeforeEach
    void setUp() {
        activeStaff = new AtomicBoolean(true);
        InstantConversationStore store = mock(InstantConversationStore.class);
        when(store.activeStaff(anyString(), anyString())).thenAnswer(call -> activeStaff.get());
        when(store.facilityWithinScope(anyString(), anyString(), anyString())).thenReturn(true);
        when(store.conversation(anyString(), anyString())).thenReturn(Optional.empty());
        StaffConversation conversation = new StaffConversation(ORG_A, FACILITY_A, CONVERSATION,
                ConversationKind.DIRECT, null, SENDER, Instant.parse("2026-11-20T10:00:00Z"));
        when(store.conversation(ORG_A, CONVERSATION)).thenReturn(Optional.of(conversation));
        when(store.participants(anyString(), anyString())).thenReturn(List.of());
        when(store.participants(ORG_A, CONVERSATION)).thenReturn(List.of(new StaffParticipant(
                ORG_A, CONVERSATION, SENDER, ParticipantRole.OWNER,
                Instant.parse("2026-11-20T10:00:00Z"), null, false)));
        when(store.messages(anyString(), anyString(), anyLong(), any())).thenReturn(List.of());
        when(store.messages(eq(ORG_A), eq(CONVERSATION), anyLong(), any())).thenReturn(List.of(
                new StaffMessage(ORG_A, MESSAGE, CONVERSATION, 1L, SENDER, "nurse", BODY,
                        Instant.parse("2026-11-20T10:01:00Z"), null)));
        InstantMessagingService service = new InstantMessagingService(store,
                Clock.fixed(Instant.parse("2026-11-20T10:02:00Z"), ZoneOffset.UTC));
        instant = StaffMessagingSecurityFixture.mvc(new InstantMessagingController(service));

        MailStore mails = mock(MailStore.class);
        when(mails.message(anyString(), anyString())).thenReturn(Optional.empty());
        when(mails.message(ORG_A, MESSAGE)).thenReturn(Optional.of(new SentMail(ORG_A, MESSAGE,
                FACILITY_A, "thread-1", "Handover", BODY, SENDER, "nurse",
                Instant.parse("2026-11-20T10:01:00Z"), List.of(), List.of(), List.of(SENDER),
                List.of(), List.of())));
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

    private MvcResult instantResult(Object id, String org, String subject, String facility) throws Exception {
        return instant.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/{id}/messages", id), org, subject)
                        .param("facilityId", facility))
                .andReturn();
    }

    private MvcResult mailResult(Object id, String org, String subject) throws Exception {
        return mail.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/mail/messages/{id}", id), org, subject))
                .andReturn();
    }

    @Test
    void missingSubjectIsUnauthorizedOnBothReadRoutes() throws Exception {
        instant.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/{id}/messages", CONVERSATION), ORG_A, null)
                        .param("facilityId", FACILITY_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mail.perform(StaffMessagingSecurityFixture.headers(
                get("/api/v1/staff/mail/messages/{id}", MESSAGE), ORG_A, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedOrganizationIsRejectedBeforeAnyLookup() throws Exception {
        instant.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/{id}/messages", CONVERSATION), "not-a-uuid", SENDER)
                        .param("facilityId", FACILITY_A))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
        mail.perform(StaffMessagingSecurityFixture.headers(
                get("/api/v1/staff/mail/messages/{id}", MESSAGE), "not-a-uuid", SENDER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
    }

    @Test
    void ghostActiveStaffIsForbiddenOnTheInstantRoute() throws Exception {
        activeStaff.set(false);
        MvcResult result = instantResult(CONVERSATION, ORG_A, GHOST, FACILITY_A);
        assertEquals(403, result.getResponse().getStatus());
        assertFalse(StaffMessagingSecurityFixture.body(result).contains(BODY),
                "a revoked subject must never see the message body");
    }

    @Test
    void crossTenantConversationIdAndRandomIdReturnTheSameConcealedBody() throws Exception {
        MvcResult foreign = instantResult(CONVERSATION, ORG_B, OUTSIDER, FACILITY_A);
        MvcResult random = instantResult(StaffMessagingSecurityFixture.randomId(), ORG_B, OUTSIDER, FACILITY_A);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals(404, random.getResponse().getStatus());
        String foreignBody = StaffMessagingSecurityFixture.body(foreign);
        String randomBody = StaffMessagingSecurityFixture.body(random);
        assertEquals(StaffMessagingSecurityFixture.normalise(randomBody),
                StaffMessagingSecurityFixture.normalise(foreignBody),
                "an existing foreign id must be indistinguishable from a guessed id");
        assertTrue(foreignBody.contains("NOT_FOUND"));
        StaffMessagingSecurityFixture.assertNoLeak(foreignBody, BODY);
    }

    @Test
    void crossTenantMailIdAndRandomIdReturnTheSameConcealedBody() throws Exception {
        MvcResult foreign = mailResult(MESSAGE, ORG_B, OUTSIDER);
        MvcResult random = mailResult(StaffMessagingSecurityFixture.randomId(), ORG_B, OUTSIDER);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals(404, random.getResponse().getStatus());
        String foreignBody = StaffMessagingSecurityFixture.body(foreign);
        String randomBody = StaffMessagingSecurityFixture.body(random);
        assertEquals(StaffMessagingSecurityFixture.normalise(randomBody),
                StaffMessagingSecurityFixture.normalise(foreignBody));
        assertTrue(foreignBody.contains("NOT_FOUND"));
        StaffMessagingSecurityFixture.assertNoLeak(foreignBody, BODY);
    }

    @Test
    void wrongFacilityInTheOwningTenantIsForbiddenWithoutLeakingTheBody() throws Exception {
        MvcResult result = instantResult(CONVERSATION, ORG_A, SENDER, FACILITY_B);
        assertEquals(403, result.getResponse().getStatus());
        String body = StaffMessagingSecurityFixture.body(result);
        assertTrue(body.contains("FORBIDDEN"));
        assertFalse(body.contains(BODY), "the facility guard must not reveal the message body");
        StaffMessagingSecurityFixture.assertNoLeak(body, BODY);
    }

    @Test
    void nonParticipantMailReaderGetsTheSameConcealedBodyAsAGuessedId() throws Exception {
        MvcResult foreign = mailResult(MESSAGE, ORG_A, StaffMessagingSecurityFixture.OTHER_SENDER);
        MvcResult random = mailResult(StaffMessagingSecurityFixture.randomId(), ORG_A,
                StaffMessagingSecurityFixture.OTHER_SENDER);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals(StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(random)),
                StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(foreign)));
        StaffMessagingSecurityFixture.assertNoLeak(StaffMessagingSecurityFixture.body(foreign), BODY);
    }

    @Test
    void owningTenantMemberStillReadsTheConversationNegativeControl() throws Exception {
        MvcResult result = instantResult(CONVERSATION, ORG_A, SENDER, FACILITY_A);
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(result).contains(BODY),
                "the refusal must be caused by the security control, not a broken fixture");
    }

    @Test
    void owningTenantSenderStillReadsTheMailNegativeControl() throws Exception {
        MvcResult result = mailResult(MESSAGE, ORG_A, SENDER);
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(result).contains(BODY),
                "the mail refusal must be caused by the security control, not a broken fixture");
    }

    @Test
    void guessedIdCannotBeUsedToSendAMessage() throws Exception {
        String guessed = StaffMessagingSecurityFixture.randomId();
        String body = "{\"facilityId\":\"" + FACILITY_A + "\",\"conversationId\":\"" + guessed
                + "\",\"body\":\"" + BODY + "\"}";
        MockHttpServletRequestBuilder request = StaffMessagingSecurityFixture.headers(
                        post("/api/v1/staff/instant/{id}/messages", guessed), ORG_B, OUTSIDER)
                .header("Idempotency-Key", "cross-tenant-send")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        instant.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(result -> StaffMessagingSecurityFixture.assertNoLeak(
                        StaffMessagingSecurityFixture.body(result), BODY));
    }
}
