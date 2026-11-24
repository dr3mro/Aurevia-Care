package com.aurevia.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationController;
import com.aurevia.messaging.conversations.ConversationRepository;
import com.aurevia.messaging.conversations.ConversationService;
import com.aurevia.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.StaffController;

/**
 * AUD-20261121-10: one canonical subject key at every request boundary.
 *
 * <p>Drives a real staff invitation command and a real conversation-open command
 * through their controllers with synthetic fixtures, then asserts that the same
 * subject text is accepted or rejected identically by both endpoints. Valid,
 * dotted, underscored, maximum-length and Arabic/RTL subjects are compared, as
 * well as colon, too-short, too-long and absent subjects. No patient data or
 * credential is present.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubjectBoundaryConsistencyTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CONNECTION_ID = "44444444-4444-4444-4444-444444444444";
    private static final String ACCEPTED = "ACCEPT";
    private static final String REJECTED = "REJECT";

    @Mock
    private StaffRepository staffRepository;
    @Mock
    private TransactionTemplate staffTransactions;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private TransactionTemplate conversationTransactions;
    @Mock
    private AuditAppender audit;
    @Mock
    private PatientConnectionVerifier connections;

    private MockMvc staffMvc;
    private MockMvc conversationMvc;

    /** Build both controllers over real domain services with synchronous transactions. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StaffService staffService = new StaffService(staffRepository, new ActionPolicy(), staffTransactions);
        doAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
                .doInTransaction((TransactionStatus) null)).when(staffTransactions).execute(any());
        lenient().when(staffRepository.hasActiveMembership(ACTOR)).thenReturn(true);
        lenient().when(staffRepository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
        lenient().when(staffRepository.findUserIdBySubject(anyString())).thenReturn(Optional.empty());
        lenient().when(staffRepository.findUserIdBySubject(ACTOR)).thenReturn(Optional.of("actor-id"));
        lenient().when(staffRepository.findBySubject(anyString())).thenAnswer(invocation -> Optional.of(
                new StaffMember(invocation.getArgument(0), "Name", "active", List.of("reception"))));
        staffMvc = MockMvcBuilders.standaloneSetup(new StaffController(staffService))
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        ConversationService conversationService = new ConversationService(conversationRepository,
                new ConversationActions(), conversationTransactions, audit, connections);
        doAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
                .doInTransaction((TransactionStatus) null)).when(conversationTransactions).execute(any());
        lenient().when(conversationRepository.hasActiveMembership(ACTOR)).thenReturn(true);
        lenient().when(conversationRepository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        lenient().when(conversationRepository.patientExists(PATIENT_ID)).thenReturn(true);
        lenient().when(conversationRepository.patientLinkActive(anyString(), eq(PATIENT_ID))).thenReturn(true);
        lenient().when(conversationRepository.find(anyString())).thenAnswer(invocation -> Optional.of(
                new Conversation(invocation.getArgument(0), 1, PATIENT_ID, "patient-a", "clinic",
                        CONNECTION_ID, "topic", State.AWAITING_PATIENT, null, 0, 0,
                        Instant.parse("2026-11-21T00:00:00Z"))));
        conversationMvc = MockMvcBuilders.standaloneSetup(new ConversationController(conversationService))
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        TenantContext.set(new TenantContext(ORG, ACTOR, "STAFF_ADMIN", "corr-subject"));
    }

    /** Clear the thread-local tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Quote a subject as a JSON value, preserving an absent subject as null. */
    private static String jsonValue(String subject) {
        return subject == null ? "null" : "\"" + subject + "\"";
    }

    /** Classify a response by whether the request boundary admitted the subject. */
    private static String decision(MvcResult result) {
        int status = result.getResponse().getStatus();
        if (status == 201) {
            return ACCEPTED;
        }
        if (status == 422) {
            return REJECTED;
        }
        return "STATUS_" + status;
    }

    /** POST the subject to the real staff invitation command. */
    private String staffDecision(String subject) throws Exception {
        String json = "{\"subject\":" + jsonValue(subject)
                + ",\"displayName\":\"Name\",\"role\":\"reception\"}";
        return decision(staffMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.getBytes(StandardCharsets.UTF_8)))
                .andReturn());
    }

    /** POST the subject to the real conversation-open command. */
    private String conversationDecision(String subject) throws Exception {
        String json = "{\"patientId\":\"" + PATIENT_ID + "\",\"patientSubject\":" + jsonValue(subject)
                + ",\"unitKey\":\"clinic\",\"connectionId\":\"" + CONNECTION_ID + "\",\"topic\":\"topic\"}";
        return decision(conversationMvc.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.getBytes(StandardCharsets.UTF_8)))
                .andReturn());
    }

    /** The identical subject gets the identical accept/reject decision on both endpoints. */
    @Test
    void staffAndConversationEndpointsAgreeOnEveryInputClass() throws Exception {
        String[] subjects = {"patient-a", "patient.one", "patient_one", "a".repeat(64),
                "مريض", "patient:one", "a", "a".repeat(65), null};
        for (String subject : subjects) {
            assertEquals(staffDecision(subject), conversationDecision(subject), "subject=" + subject);
        }
    }

    /** Dotted and underscored keys are admitted by both endpoints after the migration. */
    @Test
    void dottedAndUnderscoredSubjectsAreAcceptedByBothEndpoints() throws Exception {
        assertEquals(ACCEPTED, staffDecision("patient.one"));
        assertEquals(ACCEPTED, conversationDecision("patient.one"));
        assertEquals(ACCEPTED, staffDecision("patient_one"));
        assertEquals(ACCEPTED, conversationDecision("patient_one"));
    }

    /** The canonical maximum length is accepted by both endpoints. */
    @Test
    void maximumLengthSubjectIsAcceptedByBothEndpoints() throws Exception {
        String subject = "a".repeat(64);
        assertEquals(ACCEPTED, staffDecision(subject));
        assertEquals(ACCEPTED, conversationDecision(subject));
    }

    /** Arabic/RTL script is rejected by both endpoints with each field's subject code. */
    @Test
    void arabicSubjectIsRejectedByBothEndpoints() throws Exception {
        String json = "{\"subject\":\"مريض\",\"displayName\":\"Name\",\"role\":\"reception\"}";
        staffMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.getBytes(StandardCharsets.UTF_8)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INVALID_SUBJECT"));
        String conversationJson = "{\"patientId\":\"" + PATIENT_ID + "\",\"patientSubject\":\"مريض\","
                + "\"unitKey\":\"clinic\",\"connectionId\":\"" + CONNECTION_ID + "\",\"topic\":\"topic\"}";
        conversationMvc.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conversationJson.getBytes(StandardCharsets.UTF_8)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INVALID_PATIENTSUBJECT"));
    }
}
