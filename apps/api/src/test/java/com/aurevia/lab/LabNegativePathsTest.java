package com.aurevia.lab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionController;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabAccessionService;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * Negative paths and tenant isolation at the laboratory accession HTTP entry point (LAB-01, ARC-02).
 *
 * <p>Every case drives the real {@link com.aurevia.lab.accession.LabAccessionController}, the real
 * {@link com.aurevia.lab.accession.LabAccessionService} and the real bounded role matrix through
 * the Spring MVC pipeline. Assertions are on the HTTP status and the machine-readable error code
 * (and, for the peer-tenant case, on the absence of record fields in the raw body), so a behavior
 * that exists only in a domain kernel cannot pass. The repository is mocked: forced row-level
 * security is proven by the PostgreSQL integration suite, and here an empty lookup models the
 * fold a peer tenant sees. All identifiers, subjects, organizations and text are synthetic.</p>
 *
 * <p>The scope of this class is test-only: no production code, contract or migration is changed.
 * The repeated-key case asserts the uniqueness conflict the service has today. The standalone
 * pipeline used here has neither the idempotency filter nor the body-capture filter that the
 * production chain installs, so it deliberately does not claim replay of a frozen response.</p>
 */
class LabNegativePathsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ACCESSION_ID = "33333333-3333-3333-3333-333333333333";
    private static final String ISSUER = "LAB-A";
    private static final String NUMBER = "ACC-1";
    private static final String SECRET_NUMBER = "PEER-SECRET-ACC";
    private static final String COLLECTOR = "lab-a";
    private static final String VERIFIER = "verifier-a";
    private static final String RECEPTION = "reception-a";
    private static final String PEER_VERIFIER = "verifier-b";
    private static final String INACTIVE = "deactivated-a";
    private static final String COLLECTED_AT = "2026-11-19T10:15:30Z";
    private static final String RECEIVED_AT = "2026-11-19T11:15:30Z";
    private static final String PURPOSE = "LAB_ACCESSION";

    private LabAccessionRepository repository;
    private AuditAppender audit;
    private MockMvc mvc;

    /** Wire the real controller and domain service behind the tenant-aware MVC pipeline. */
    @BeforeEach
    void setUp() {
        repository = mock(LabAccessionRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        LabAccessionService service = new LabAccessionService(repository, new LabActionPolicy(),
                transactions, audit);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new LabAccessionController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Release the thread-bound context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant an active membership and a role set to one synthetic subject. */
    private void active(String subject, String... roles) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(roles));
    }

    /** Post an accession command, optionally carrying an idempotency key. */
    private ResultActions accession(String number, String subject, String organization, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/lab/accessions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject)
                .content(accessionBody(number, ISSUER, PATIENT_A, "ORD-1"));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    /** Post a collection command for the synthetic specimen. */
    private ResultActions collect(String id, String body, String subject, String organization)
            throws Exception {
        return mvc.perform(post("/api/v1/lab/accessions/{id}/collect", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject)
                .content(body));
    }

    /** Post a receipt command for the synthetic specimen. */
    private ResultActions receive(String id, String body, String subject, String organization)
            throws Exception {
        return mvc.perform(post("/api/v1/lab/accessions/{id}/receive", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject)
                .content(body));
    }

    /** Read one accession as the given synthetic subject and organization. */
    private ResultActions readAccession(String id, String subject, String organization) throws Exception {
        return mvc.perform(get("/api/v1/lab/accessions/{id}", id)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject));
    }

    /** Build the accession command, leaving one field corrupt while the rest stay valid. */
    private static String accessionBody(String number, String issuer, String patient, String order) {
        return "{\"accessionNo\":" + quoted(number)
                + ",\"accessionIssuer\":" + quoted(issuer)
                + ",\"patientId\":" + quoted(patient)
                + ",\"sourceOrderRef\":" + quoted(order) + "}";
    }

    /** Build a collection command with an independently corrupt timestamp or version. */
    private static String collectBody(String patient, String number, String collectedAt, int version) {
        return "{\"patientId\":" + quoted(patient)
                + ",\"accessionNo\":" + quoted(number)
                + ",\"accessionIssuer\":" + quoted(ISSUER)
                + ",\"specimenType\":\"PLASMA\",\"siteCode\":\"SITE-A\",\"collector\":\"Sam Collector\""
                + ",\"collectedAt\":" + quoted(collectedAt)
                + ",\"expectedVersion\":" + version + "}";
    }

    /** Build a receipt command whose identity fields match the stored synthetic requisition. */
    private static String receiveBody(String patient, String number, int version) {
        return "{\"patientId\":" + quoted(patient)
                + ",\"accessionNo\":" + quoted(number)
                + ",\"accessionIssuer\":" + quoted(ISSUER)
                + ",\"quality\":\"acceptable\",\"receivedAt\":" + quoted(RECEIVED_AT)
                + ",\"expectedVersion\":" + version + "}";
    }

    /** Render a nullable field as a JSON literal so a missing value stays distinguishable. */
    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** Build a deterministic synthetic projection in the requested lifecycle state and version. */
    private static LabAccession projection(String id, int version, String number, String patient,
                                           AccessionState state) {
        return new LabAccession(id, version, number, ISSUER, patient, "ORD-1", state,
                "PLASMA", "SITE-A", "Sam Collector", COLLECTED_AT, null, null, null, null, null);
    }

    /** Missing, oversized and malformed credentials fail at the trust boundary before any lookup. */
    @Test
    void missingAndOversizedNumberAndMalformedIdAreRejected() throws Exception {
        accession(null, COLLECTOR, ORG_A, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESSION"));
        accession("A".repeat(65), COLLECTOR, ORG_A, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESSION"));
        readAccession("not-a-uuid", COLLECTOR, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
        mvc.perform(post("/api/v1/lab/accessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Synthetic-Subject", COLLECTOR)
                        .header("X-Active-Organization", ORG_A)
                        .header("X-Correlation-Id", "corr-" + COLLECTOR)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"));

        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).findById(anyString());
    }

    /** Both branches of instant parsing reject a missing or unreadable collection time. */
    @Test
    void missingAndUnparseableCollectionTimeAreRejected() throws Exception {
        collect(ACCESSION_ID, collectBody(PATIENT_A, NUMBER, null, 1), COLLECTOR, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TIMESTAMP"));
        collect(ACCESSION_ID, collectBody(PATIENT_A, NUMBER, "not-an-instant", 1), COLLECTOR, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TIMESTAMP"));

        verify(repository, never()).findById(anyString());
    }

    /** A non-positive expected version is rejected before a transaction opens. */
    @Test
    void zeroExpectedVersionIsRejected() throws Exception {
        collect(ACCESSION_ID, collectBody(PATIENT_A, NUMBER, COLLECTED_AT, 0), COLLECTOR, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION"));

        verify(repository, never()).findById(anyString());
    }

    /** A wrong duty and a missing membership are both denied and audited before any lookup. */
    @Test
    void wrongRoleAndInactiveMembershipAreForbiddenOnReceive() throws Exception {
        String body = receiveBody(PATIENT_A, NUMBER, 1);
        active(RECEPTION, "reception");
        receive(ACCESSION_ID, body, RECEPTION, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        active(COLLECTOR, "lab_collector");
        receive(ACCESSION_ID, body, COLLECTOR, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        receive(ACCESSION_ID, body, INACTIVE, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(audit, times(3)).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_RECEIVE),
                eq("ACCESSION"), eq("LAB"), eq(ACCESSION_ID), eq("DENIED"));
        verify(repository, never()).findById(anyString());
    }

    /**
     * A peer tenant receives a concealed not-found, never the owner's accession number or patient.
     *
     * <p>Both tenants hold the same verifier role in their own organization, so the decision is
     * made by the tenant fold rather than by role. The owner sees the synthetic row; the peer's
     * lookup is empty, exactly as forced row-level security makes it. Only {@code get} writes a
     * NOT_FOUND audit row, which is why this case uses the read route.</p>
     */
    @Test
    void peerTenantReadIsConcealedAsNotFound() throws Exception {
        active(VERIFIER, "lab_verifier");
        active(PEER_VERIFIER, "lab_verifier");
        LabAccession owned = projection(ACCESSION_ID, 1, SECRET_NUMBER, PATIENT_A,
                AccessionState.ACCESSIONED);
        when(repository.findById(ACCESSION_ID)).thenAnswer(invocation ->
                ORG_A.equals(TenantContext.current().organizationId())
                        ? Optional.of(owned) : Optional.empty());
        when(repository.history(ACCESSION_ID)).thenReturn(List.of());

        String ownerBody = readAccession(ACCESSION_ID, VERIFIER, ORG_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accession.accessionNo").value(SECRET_NUMBER))
                .andExpect(jsonPath("$.accession.patientId").value(PATIENT_A))
                .andReturn().getResponse().getContentAsString();
        assertTrue(ownerBody.contains(SECRET_NUMBER));

        String peerBody = readAccession(ACCESSION_ID, PEER_VERIFIER, ORG_B)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("accession not found"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(peerBody.contains(SECRET_NUMBER));
        assertFalse(peerBody.contains(PATIENT_A));
        assertFalse(peerBody.contains(ORG_A));

        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_READ),
                eq("ACCESSION"), eq("LAB"), eq(ACCESSION_ID), eq("NOT_FOUND"));
    }

    /** A version behind the stored row is rejected before any update is attempted. */
    @Test
    void versionBehindTheRowIsStale() throws Exception {
        active(VERIFIER, "lab_verifier");
        when(repository.findById(ACCESSION_ID)).thenReturn(Optional.of(
                projection(ACCESSION_ID, 2, NUMBER, PATIENT_A, AccessionState.COLLECTED)));

        receive(ACCESSION_ID, receiveBody(PATIENT_A, NUMBER, 1), VERIFIER, ORG_A)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    /** A lost compare-and-set returns the same stale-version refusal as an outdated precondition. */
    @Test
    void lostCompareAndSetIsStale() throws Exception {
        active(COLLECTOR, "lab_collector");
        when(repository.findById(ACCESSION_ID)).thenReturn(Optional.of(
                projection(ACCESSION_ID, 1, NUMBER, PATIENT_A, AccessionState.ACCESSIONED)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(0);

        collect(ACCESSION_ID, collectBody(PATIENT_A, NUMBER, COLLECTED_AT, 1), COLLECTOR, ORG_A)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        verify(repository).update(anyString(), anyString(), anyInt(),
                eq(AccessionState.COLLECTED), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Repeating one keyed accession command creates only the first row: the second is a conflict.
     *
     * <p>This is issuer-scoped business uniqueness, not a replayed idempotency response: the
     * standalone pipeline has no idempotency filter, so the mock verifications assert that no
     * second insert or history event is produced.</p>
     */
    @Test
    void repeatedKeyedCommandConflictsWithoutReplay() throws Exception {
        active(COLLECTOR, "lab_collector");
        when(repository.accessionExists(ISSUER, NUMBER)).thenReturn(false, true);
        when(repository.patientExists(PATIENT_A)).thenReturn(true);
        when(repository.findById(anyString())).thenAnswer(invocation -> Optional.of(
                projection(invocation.getArgument(0), 1, NUMBER, PATIENT_A,
                        AccessionState.ACCESSIONED)));

        accession(NUMBER, COLLECTOR, ORG_A, "lab-retry-key")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessionNo").value(NUMBER));
        accession(NUMBER, COLLECTOR, ORG_A, "lab-retry-key")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        verify(repository, times(1)).insert(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, times(1)).appendEvent(anyString(), eq(1), eq("accessioned"), any(), any(),
                any(), any());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_CREATE),
                eq("ACCESSION"), eq("LAB"), eq(NUMBER), eq("CONFLICT"));
    }

    /** A different patient at collection does not match the stored requisition. */
    @Test
    void mismatchedPatientIsRejected() throws Exception {
        active(COLLECTOR, "lab_collector");
        when(repository.findById(ACCESSION_ID)).thenReturn(Optional.of(
                projection(ACCESSION_ID, 1, NUMBER, PATIENT_A, AccessionState.ACCESSIONED)));

        collect(ACCESSION_ID, collectBody(OTHER_PATIENT, NUMBER, COLLECTED_AT, 1), COLLECTOR, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESSION_MISMATCH"));

        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    /** An accession that has not been collected cannot be received. */
    @Test
    void illegalTransitionIsRejected() throws Exception {
        active(VERIFIER, "lab_verifier");
        when(repository.findById(ACCESSION_ID)).thenReturn(Optional.of(
                projection(ACCESSION_ID, 1, NUMBER, PATIENT_A, AccessionState.ACCESSIONED)));

        receive(ACCESSION_ID, receiveBody(PATIENT_A, NUMBER, 1), VERIFIER, ORG_A)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }
}
