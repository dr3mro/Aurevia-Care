package com.aurevia.staffmsg.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.references.ReferenceContract.AccessOutcome;
import com.aurevia.staffmsg.references.ReferenceContract.AttachResult;
import com.aurevia.staffmsg.references.ReferenceContract.NotificationDraft;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceActor;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceAudit;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceKind;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** HTTP entry-point evidence for typed message references through the normal tenant filter. */
class ReferenceControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SENDER = "nurse-a";
    private static final String TARGET = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String LABEL = "Visit summary";
    private static final String BASE = "/api/v1/staff/references";

    private FakeAccess access;
    private CapturingAuditor auditor;
    private CapturingNotifier notifier;
    private ReferenceService service;
    private ReferenceController controller;
    private MockMvc mvc;

    /** Build the controller over synthetic ports using the production constructor. */
    @BeforeEach
    void setUp() {
        access = new FakeAccess();
        auditor = new CapturingAuditor();
        notifier = new CapturingNotifier();
        service = new ReferenceService(access, auditor, notifier,
                Clock.fixed(Instant.parse("2026-11-23T09:00:00Z"), ZoneOffset.UTC));
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SENDER)).thenReturn(List.of("nurse"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new ReferenceController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Clear the thread-local tenant after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String attachBody() {
        return "{\"kind\":\"patient\",\"targetId\":\"" + TARGET + "\",\"connectionId\":\"" + CONNECTION
                + "\",\"label\":\"" + LABEL + "\",\"subject\":\"chart MRN: 42\"}";
    }

    private static String handleBody(String id) {
        return "{\"id\":\"" + id + "\",\"connectionId\":\"" + CONNECTION + "\",\"kind\":\"patient\","
                + "\"targetId\":\"" + TARGET + "\",\"label\":\"" + LABEL + "\"}";
    }

    /** Attaching through HTTP returns 201, an opaque reference and an identifier-safe warning. */
    @Test
    void attachReturnsCreatedWithWarning() throws Exception {
        mvc.perform(post(BASE + "/attach")
                        .contentType(MediaType.APPLICATION_JSON).content(attachBody())
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference.kind").value("patient"))
                .andExpect(jsonPath("$.reference.label").value(LABEL))
                .andExpect(jsonPath("$.warning.suspicious").value(true))
                .andExpect(jsonPath("$.warning.reasonCode").value("MRN_LIKE"));
        assertEquals(1, auditor.audits.size());
    }

    /** The view route exposes only a label, never the target or connection identifier. */
    @Test
    void viewReturnsLabelOnly() throws Exception {
        AttachResult result = service.attach(ReferenceActor.staff(ORG, SENDER, "nurse"),
                new ReferenceContract.AttachRequest("patient", TARGET, CONNECTION, LABEL, "follow up"));
        String body = mvc.perform(get(BASE + "/view")
                        .param("id", result.reference().id())
                        .param("connectionId", CONNECTION)
                        .param("kind", "patient")
                        .param("targetId", TARGET)
                        .param("label", LABEL)
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value(LABEL))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("targetId"));
        assertFalse(body.contains(TARGET));
        assertFalse(body.contains(CONNECTION));
    }

    /** A recipient without a care relationship gets a blocked state with no target data. */
    @Test
    void openIsBlockedWithoutRelationship() throws Exception {
        AttachResult result = service.attach(ReferenceActor.staff(ORG, SENDER, "nurse"),
                new ReferenceContract.AttachRequest("patient", TARGET, CONNECTION, LABEL, "follow up"));
        access.outcome = AccessOutcome.NO_RELATIONSHIP;
        mvc.perform(post(BASE + "/open")
                        .contentType(MediaType.APPLICATION_JSON).content(handleBody(result.reference().id()))
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REFERENCE_BLOCKED"));
        assertTrue(notifier.drafts.isEmpty());
    }

    /** An authorized recipient opens the reference and a clean notification is dispatched. */
    @Test
    void openSucceedsForAuthorizedRecipient() throws Exception {
        AttachResult result = service.attach(ReferenceActor.staff(ORG, SENDER, "nurse"),
                new ReferenceContract.AttachRequest("patient", TARGET, CONNECTION, LABEL, "follow up"));
        access.outcome = AccessOutcome.ALLOWED;
        mvc.perform(post(BASE + "/open")
                        .contentType(MediaType.APPLICATION_JSON).content(handleBody(result.reference().id()))
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("patient"))
                .andExpect(jsonPath("$.targetId").value(TARGET));
        assertEquals(1, notifier.drafts.size());
        for (NotificationDraft draft : notifier.drafts) {
            assertFalse(draft.text().contains(TARGET));
        }
    }

    /** A cross-tenant target is concealed as a not-found. */
    @Test
    void openConcealsWrongTenantTarget() throws Exception {
        AttachResult result = service.attach(ReferenceActor.staff(ORG, SENDER, "nurse"),
                new ReferenceContract.AttachRequest("patient", TARGET, CONNECTION, LABEL, "follow up"));
        access.outcome = AccessOutcome.WRONG_TENANT;
        mvc.perform(post(BASE + "/open")
                        .contentType(MediaType.APPLICATION_JSON).content(handleBody(result.reference().id()))
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFERENCE_NOT_FOUND"));
    }

    /** No login means no actor and a fail-closed 401 at the tenant boundary. */
    @Test
    void attachWithoutLoginReturns401() throws Exception {
        mvc.perform(post(BASE + "/attach")
                        .contentType(MediaType.APPLICATION_JSON).content(attachBody())
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** The actor derivation itself fails closed when no tenant context exists. */
    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        ApiException denied = assertThrows(ApiException.class, () ->
                controller.view("id", CONNECTION, "patient", TARGET, LABEL));
        assertEquals(401, denied.status());
        assertEquals("UNAUTHENTICATED", denied.code());
    }

    /** The sender-side pre-check exposes a warning without any patient data. */
    @Test
    void screenSubjectReturnsWarning() throws Exception {
        mvc.perform(post(BASE + "/screen-subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"nurse@example.test\"}")
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspicious").value(true))
                .andExpect(jsonPath("$.reasonCode").value("EMAIL_LIKE"));
    }

    /** The package-private fixture constructor lets a standalone view use a fixed actor. */
    @Test
    void fixtureConstructorUsesTheFixedActor() throws Exception {
        AttachResult result = service.attach(ReferenceActor.staff(ORG, SENDER, "nurse"),
                new ReferenceContract.AttachRequest("patient", TARGET, CONNECTION, LABEL, "follow up"));
        ReferenceController fixture = new ReferenceController(service,
                ReferenceActor.staff(ORG, "fixture-actor", "nurse"));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        MockMvc fixtureMvc = MockMvcBuilders.standaloneSetup(fixture)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        fixtureMvc.perform(post(BASE + "/attach")
                        .contentType(MediaType.APPLICATION_JSON).content(attachBody())
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference.kind").value("patient"));
        assertEquals("fixture-actor",
                auditor.audits.get(auditor.audits.size() - 1).actorSubject());
    }

    /** Settable access outcome used to model care-relationship presence per test. */
    private static final class FakeAccess implements ReferenceAccess {
        private AccessOutcome outcome = AccessOutcome.ALLOWED;

        @Override
        public AccessOutcome check(String organizationId, String subject, ReferenceKind kind,
                                   String targetId, String connectionId) {
            return outcome;
        }
    }

    /** Captures audits so HTTP tests can assert the operation wrote a fact. */
    private static final class CapturingAuditor implements ReferenceAuditor {
        private final List<ReferenceAudit> audits = new ArrayList<>();

        @Override
        public void append(ReferenceAudit audit) {
            audits.add(audit);
        }
    }

    /** Captures notification drafts so HTTP tests can assert no identifiers leaked. */
    private static final class CapturingNotifier implements ReferenceNotifier {
        private final List<NotificationDraft> drafts = new ArrayList<>();

        @Override
        public void dispatch(NotificationDraft draft) {
            drafts.add(draft);
        }
    }
}
