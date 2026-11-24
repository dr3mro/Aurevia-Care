package com.aurevia.pharmacyflow.substitution;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

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
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.substitution.SubstitutionController.DecideRequest;
import com.aurevia.pharmacyflow.substitution.SubstitutionController.ProposeRequest;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** RX-01/RX-02 substitution API: propose, approve, decline and read through HTTP. */
class SubstitutionControllerHttpTest {

    private static final String BASE = "/api/v1/pharmacy/substitutions";

    private MockMvc mvc;
    private ObjectMapper mapper;
    private FakeSubstitutionCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new FakeSubstitutionCatalog();
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(anyString())).thenAnswer(invocation -> {
            String subject = invocation.getArgument(0);
            if (SubstitutionFixtures.PRESCRIBER.equals(subject)
                    || SubstitutionFixtures.OTHER_CLINICIAN.equals(subject)) {
                return List.of(SpecialtyRoles.CLINICIAN);
            }
            if (SubstitutionFixtures.PHARMACIST.equals(subject)) {
                return List.of(SpecialtyRoles.PHARMACY_STAFF);
            }
            return List.of(SpecialtyRoles.RECEPTION);
        });
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(org.mockito.ArgumentMatchers.any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(mock(TransactionStatus.class));
                });
        SubstitutionService service = new SubstitutionService(
                Clock.fixed(SubstitutionFixtures.AT, ZoneOffset.UTC), catalog,
                new FakeSubstitutionStore(), new FakeSubstitutionApplication(), transactions,
                mock(com.aurevia.audit.AuditAppender.class));
        SubstitutionController controller = new SubstitutionController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request,
                                                  String subject, String org) {
        return request.header("X-Synthetic-Subject", subject).header("X-Active-Organization", org);
    }

    private ResultActions propose(String proposedId, String reason, String subject, String org, String key)
            throws Exception {
        return propose(proposedId, reason, subject, org, key, null);
    }

    private ResultActions propose(String proposedId, String reason, String subject, String org, String key,
                                  String overrideReason) throws Exception {
        String body = mapper.writeValueAsString(new ProposeRequest(SubstitutionFixtures.ORIGINAL_ID,
                proposedId, reason, "stock unavailable", overrideReason));
        return mvc.perform(headers(post(BASE + "/{id}/proposals", SubstitutionFixtures.PRESCRIPTION), subject, org)
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", key));
    }

    private String extractProposalId(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString();
        int start = json.indexOf("\"proposal\":{\"id\":\"");
        int from = start + "\"proposal\":{\"id\":\"".length();
        return json.substring(from, json.indexOf('\"', from));
    }

    private ResultActions decide(String proposalId, String action, String subject, String attestation,
                                 String note, String ifMatch, String key) throws Exception {
        String body = mapper.writeValueAsString(new DecideRequest(attestation, note));
        return mvc.perform(headers(post(BASE + "/{id}/" + action, proposalId), subject,
                SubstitutionFixtures.ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("If-Match", ifMatch).header("Idempotency-Key", key));
    }

    private ResultActions view(String proposalId, String subject, String org) throws Exception {
        return mvc.perform(headers(get(BASE + "/{id}", proposalId), subject, org));
    }

    @Test
    void proposeThroughHttpCreatesPendingProposalWithNoRevision() throws Exception {
        propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock", SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.ORG, "http-propose")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.state").value("pending"))
                .andExpect(jsonPath("$.proposal.original.entryId").value(SubstitutionFixtures.ORIGINAL_ID))
                .andExpect(jsonPath("$.proposal.proposed.entryId").value(SubstitutionFixtures.ALTERNATIVE_ID))
                .andExpect(jsonPath("$.proposal.reason").value("stock"))
                .andExpect(jsonPath("$.proposal.allergyCheck.outcome").value("no_conflict"))
                .andExpect(jsonPath("$.alternatives.length()").value(1))
                .andExpect(jsonPath("$.appliedRevision").doesNotExist());
    }

    @Test
    void approvalThroughHttpCreatesSignedRevisionWithProvenance() throws Exception {
        String id = extractProposalId(propose(SubstitutionFixtures.ALTERNATIVE_ID, "brand",
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.ORG, "http-approve-propose"));

        decide(id, "approve", SubstitutionFixtures.PRESCRIBER, SubstitutionFixtures.ATTESTATION,
                null, "1", "http-approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.state").value("approved"))
                .andExpect(jsonPath("$.appliedRevision.proposalId").value(id))
                .andExpect(jsonPath("$.appliedRevision.replaced.entryId").value(SubstitutionFixtures.ORIGINAL_ID))
                .andExpect(jsonPath("$.appliedRevision.replacement.entryId").value(SubstitutionFixtures.ALTERNATIVE_ID))
                .andExpect(jsonPath("$.appliedRevision.reason").value("brand"))
                .andExpect(jsonPath("$.appliedRevision.approvedBy").value(SubstitutionFixtures.PRESCRIBER))
                .andExpect(jsonPath("$.appliedRevision.attestation").value(SubstitutionFixtures.ATTESTATION))
                .andExpect(jsonPath("$.appliedRevision.catalogProvenance").value("catalog-v3-approved"))
                .andExpect(jsonPath("$.appliedRevision.revisionNumber").value(1));
    }

    @Test
    void declineThroughHttpKeepsOriginalAndCreatesNoRevision() throws Exception {
        String id = extractProposalId(propose(SubstitutionFixtures.ALTERNATIVE_ID, "formulation",
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.ORG, "http-decline-propose"));

        decide(id, "decline", SubstitutionFixtures.PRESCRIBER, null, "not now", "1", "http-decline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.state").value("declined"))
                .andExpect(jsonPath("$.proposal.note").value("not now"))
                .andExpect(jsonPath("$.proposal.original.entryId").value(SubstitutionFixtures.ORIGINAL_ID))
                .andExpect(jsonPath("$.appliedRevision").doesNotExist());

        view(id, SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.state").value("declined"));
    }

    @Test
    void onlyCatalogAlternativeCanBeProposedThroughHttp() throws Exception {
        propose(SubstitutionFixtures.OTHER_DRUG_ID, "stock", SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.ORG, "http-not-alternative")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_CATALOG_ALTERNATIVE"));
    }

    @Test
    void allergyStrengthAndRouteGatesAreEnforcedThroughHttp() throws Exception {
        catalog.withAllergies(SubstitutionFixtures.conflictingAllergies());
        propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock", SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.ORG, "http-allergy")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALLERGY_OVERRIDE_REQUIRED"));

        propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock", SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.ORG, "http-allergy-override", "documented override")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.allergyCheck.outcome").value("conflict"));

        catalog.withAllergies(SubstitutionFixtures.noAllergies());
        catalog.withEntries(List.of(SubstitutionFixtures.original(), SubstitutionFixtures.strongerAlternative()));
        propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock", SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.ORG, "http-strength")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_EQUIVALENT_STRENGTH"));

        catalog.withEntries(SubstitutionFixtures.entries());
        catalog.withRoute("injectable");
        propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock", SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.ORG, "http-route")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROUTE_MISMATCH"));
    }

    @Test
    void wrongRolesAndOutsidersAreRejectedThroughHttp() throws Exception {
        propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock", "reception-user",
                SubstitutionFixtures.ORG, "http-wrong-propose-role")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String id = extractProposalId(propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock",
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.ORG, "http-isolation-propose"));

        decide(id, "approve", SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.ATTESTATION,
                null, "1", "http-wrong-decide-role")
                .andExpect(status().isForbidden());
        decide(id, "approve", SubstitutionFixtures.OTHER_CLINICIAN, SubstitutionFixtures.ATTESTATION,
                null, "1", "http-other-clinician")
                .andExpect(status().isNotFound());
        view(id, SubstitutionFixtures.OTHER_CLINICIAN, SubstitutionFixtures.ORG)
                .andExpect(status().isNotFound());
        view(id, SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.OTHER_ORG)
                .andExpect(status().isNotFound());
    }

    @Test
    void preconditionsAndStaleVersionAreEnforcedThroughHttp() throws Exception {
        String proposeBody = mapper.writeValueAsString(new ProposeRequest(SubstitutionFixtures.ORIGINAL_ID,
                SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null));
        mvc.perform(headers(post(BASE + "/{id}/proposals", SubstitutionFixtures.PRESCRIPTION),
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(proposeBody))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        String id = extractProposalId(propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock",
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.ORG, "http-preconditions"));

        String decideBody = mapper.writeValueAsString(new DecideRequest(SubstitutionFixtures.ATTESTATION, null));
        mvc.perform(headers(post(BASE + "/{id}/approve", id), SubstitutionFixtures.PRESCRIBER,
                SubstitutionFixtures.ORG).contentType(MediaType.APPLICATION_JSON).content(decideBody)
                        .header("Idempotency-Key", "http-no-ifmatch"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        decide(id, "approve", SubstitutionFixtures.PRESCRIBER, SubstitutionFixtures.ATTESTATION,
                null, "2", "http-stale")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        decide(id, "approve", SubstitutionFixtures.PRESCRIBER, null,
                null, "1", "http-no-attestation")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ATTESTATION_REQUIRED"));
    }
}
