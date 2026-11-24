package com.aurevia.it.patientidentity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.messaging.conversations.ConversationInputs;
import com.aurevia.patient.identity.IdentityActionPolicy;
import com.aurevia.patient.identity.IdentityProofingController;
import com.aurevia.patient.identity.IdentityProofingRepository;
import com.aurevia.patient.identity.IdentityProofingService;
import com.aurevia.patient.identity.PatientAccount;
import com.aurevia.tenant.TenantContext;

/**
 * AUD-20261121-15: one canonical lowercase subject key at the identity boundary.
 *
 * <p>The identity-proofing endpoint used to accept {@code . : _} anywhere with a
 * three-character minimum while the conversation, finance, prescription and
 * critical-result boundaries rejected dots, underscores and colons. The canonical
 * bounded key is {@code [a-z0-9][a-z0-9._-]{1,63}}: an alphanumeric start, 2-64
 * lowercase letters, digits, dots, underscores or hyphens, no colon and no
 * non-ASCII script. These tests compare the identity endpoint with that rule and
 * with the conversation request boundary on identical synthetic inputs. All
 * fixtures are synthetic; no patient data or credential is used.</p>
 */
@ExtendWith(MockitoExtension.class)
class SubjectValidationConsistencyTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String STAFF = "registrar-a";
    private static final String VALID_NAME = "Patient One";
    private static final String ACCEPTED = "ACCEPT";
    private static final String INVALID = "INVALID_SUBJECT";

    /** The shared bounded subject rule every request boundary must apply. */
    private static final Pattern CANONICAL_SUBJECT = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");

    @Mock
    private IdentityProofingRepository repository;
    @Mock
    private IdentityActionPolicy policy;

    private IdentityProofingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new IdentityProofingService(repository, policy,
                new TransactionTemplate(new PassthroughTransactionManager()));
        TenantContext.set(new TenantContext(ORG, STAFF, "PATIENT_IDENTITY", "corr-subject"));
        // Lenient: rejection-path tests deliberately never reach the boundary mocks.
        lenient().when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        lenient().when(repository.activeRoles(STAFF)).thenReturn(List.of("tenant_admin"));
        lenient().when(policy.allows(anyString(), anyString())).thenReturn(true);
        lenient().when(repository.accountExists(anyString())).thenReturn(false);
        lenient().when(repository.findAccount(anyString())).thenAnswer(invocation -> Optional.of(
                new PatientAccount("acct-1", invocation.getArgument(0), VALID_NAME)));
        mvc = MockMvcBuilders.standaloneSetup(new IdentityProofingController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Runs callbacks directly so the boundary is exercised without a database. */
    private static final class PassthroughTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no durable state in unit tests
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no durable state in unit tests
        }
    }

    /** The decision the identity-proofing command applies to one subject. */
    private String identityDecision(String subject) {
        try {
            service.enroll(subject, VALID_NAME);
            return ACCEPTED;
        } catch (ApiException exception) {
            return exception.code();
        }
    }

    /** The decision the canonical bounded-key rule applies to one subject. */
    private String canonicalDecision(String subject) {
        return subject != null && CANONICAL_SUBJECT.matcher(subject).matches() ? ACCEPTED : INVALID;
    }

    /** The decision the conversation request boundary applies to one subject. */
    private String conversationDecision(String subject) {
        try {
            ConversationInputs.subject(subject, "subject");
            return ACCEPTED;
        } catch (ApiException exception) {
            return exception.code();
        }
    }

    @Test
    void identityAppliesTheCanonicalRuleToEveryInputClass() {
        assertAll(
                () -> assertEquals(ACCEPTED, identityDecision("patient-one"), "hyphenated"),
                () -> assertEquals(ACCEPTED, identityDecision("patient.one"), "dotted"),
                () -> assertEquals(ACCEPTED, identityDecision("patient_one"), "underscored"),
                () -> assertEquals(ACCEPTED, identityDecision("ab"), "minimum length"),
                () -> assertEquals(ACCEPTED, identityDecision("a".repeat(64)), "maximum length"),
                () -> assertEquals(INVALID, identityDecision("patient:one"), "colon"),
                () -> assertEquals(INVALID, identityDecision("a"), "too short"),
                () -> assertEquals(INVALID, identityDecision(".patient"), "leading dot"),
                () -> assertEquals(INVALID, identityDecision("_patient"), "leading underscore"),
                () -> assertEquals(INVALID, identityDecision("-patient"), "leading hyphen"),
                () -> assertEquals(INVALID, identityDecision("Patient"), "uppercase"),
                () -> assertEquals(INVALID, identityDecision("مريض"), "Arabic/RTL"),
                () -> assertEquals(INVALID, identityDecision("a".repeat(65)), "too long"),
                () -> assertEquals(INVALID, identityDecision(null), "null"));
    }

    @Test
    void identityAndConversationAgreeOnSharedInputClasses() {
        String[] subjects = {"patient-one", "ab", "a".repeat(64), "patient:one", "a", ".patient",
                "_patient", "-patient", "Patient", "مريض", "a".repeat(65), null};
        for (String subject : subjects) {
            assertEquals(conversationDecision(subject), identityDecision(subject),
                    "subject=" + subject);
        }
    }

    @Test
    void dottedAndUnderscoredSubjectsStayValidUnderTheCanonicalRule() {
        // AUD-20261121-10 migrated the conversation request boundary to this same
        // canonical primitive, so the shared rule is both the parity oracle and
        // the decision each migrated boundary now applies.
        assertEquals(ACCEPTED, canonicalDecision("patient.one"));
        assertEquals(ACCEPTED, canonicalDecision("patient_one"));
        assertEquals(canonicalDecision("patient.one"), identityDecision("patient.one"));
        assertEquals(canonicalDecision("patient_one"), identityDecision("patient_one"));
    }

    @Test
    void dottedAndUnderscoredParityAfterTheConversationMigration() {
        // AUD-20261121-10 migrated ConversationInputs to the canonical primitive,
        // so identity and conversation now agree on these exact inputs. The former
        // divergence assertion is replaced by parity; see the staff/conversation
        // HTTP parity test in staff.SubjectBoundaryConsistencyTest for the other
        // request boundary pair named by that story.
        assertEquals(ACCEPTED, identityDecision("patient.one"));
        assertEquals(ACCEPTED, conversationDecision("patient.one"));
        assertEquals(ACCEPTED, identityDecision("patient_one"));
        assertEquals(ACCEPTED, conversationDecision("patient_one"));
    }

    @Test
    void identityEndpointRejectsAColonSubjectWithTheSharedErrorCode() throws Exception {
        mvc.perform(post("/api/v1/patient-identity/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"patient:one\",\"displayName\":\"" + VALID_NAME + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(INVALID));
    }

    @Test
    void identityEndpointAcceptsADottedCanonicalSubject() throws Exception {
        mvc.perform(post("/api/v1/patient-identity/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"patient.one\",\"displayName\":\"" + VALID_NAME + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject").value("patient.one"));
    }
}
