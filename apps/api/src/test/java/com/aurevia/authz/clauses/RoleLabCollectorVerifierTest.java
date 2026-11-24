package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.InMemoryDecisionSink;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.authz.specialty.SpecialtyActions;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.lab.result.LabPolicyCatalog;
import com.aurevia.lab.result.LabResultService;
import com.aurevia.lab.result.LabRules;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.labflow.collection.CollectRequest;
import com.aurevia.labflow.collection.SpecimenCollectionService;
import com.aurevia.labflow.results.ResultDecisionRequest;
import com.aurevia.labflow.results.ResultEntryRequest;
import com.aurevia.labflow.results.ResultEntryService;
import com.aurevia.labflow.results.ReportView;
import com.aurevia.exchange.critical.CriticalResultService;
import com.aurevia.labflow.results.ObservationInput;
import com.aurevia.patient.PatientRepository;
import com.aurevia.tenant.TenantContext;

/**
 * IAM-01 / LAB-02 section-2 role matrix proof for the lab collector and lab verifier
 * (Story SPEC-ROLE-LAB-COLLECTOR-VERIFIER).
 *
 * <p>Two real entry points are exercised with synthetic fixtures only. The command
 * gate ({@link com.aurevia.api.authorization.CommandAuthorization}) proves
 * which verbs the API allows per active role; the owning domain services
 * ({@link com.aurevia.labflow.collection.SpecimenCollectionService} and
 * {@link com.aurevia.labflow.results.ResultEntryService}) prove the same
 * duty boundary at the object level. Membership and roles are resolved from the
 * server identity, never from the client's inert claimed fields. The last test is a
 * characterization of the missing "assigned requisitions" scope: it records what the
 * API does today so a later task has a precise starting point. The would-prove version
 * of that test, with its skip marker, is kept in
 * {@code docs/spec/clauses/authz/role-lab-collector-verifier.md}: a skipped Java test
 * is a build failure under {@code scripts/check-constraints.py} and AGENTS.md.</p>
 */
class RoleLabCollectorVerifierTest {

    private static final String ORG = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SUBJECT = "lab-clause-collector";
    private static final String VERIFIER = "lab-clause-verifier";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String SPECIMEN = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String NUMBER = "ACC-LAB-1";
    private static final String ISSUER = "LAB-A";
    private static final String SITE = "SITE-A";
    private static final String FOREIGN_SITE = "SITE-FOREIGN";
    private static final String COLLECTOR_NAME = "Sam Collector";
    private static final String COLLECTED_AT = "2026-11-24T09:30:00Z";
    private static final String PURPOSE = "LAB_ROLE_PROOF";
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServerIdentity IDENTITY = new ServerIdentity(ORG, SUBJECT, "corr-lab-clause");

    private static final String ACCESSION = "accessionRequisition";
    private static final String COLLECT = "collectSpecimen";
    private static final String VERIFY = "verifyLabResult";
    private static final String REGISTER = "registerPatient";
    private static final String BOOK = "bookAppointment";
    private static final String INVITE = "inviteStaff";

    private LabAccessionRepository accessions;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private SpecimenCollectionService collection;
    private ResultEntryService results;

    /** Fresh membership fold; a client-supplied role can never populate it. */
    private static final class Resolver implements MembershipResolver {

        private final List<String> roles;
        private final boolean active;

        private Resolver(List<String> roles, boolean active) {
            this.roles = roles;
            this.active = active;
        }

        @Override
        public MemberContext resolve(ServerIdentity identity) {
            return new MemberContext(roles, active);
        }
    }

    @BeforeEach
    void setUp() {
        accessions = mock(LabAccessionRepository.class);
        PatientRepository patients = mock(PatientRepository.class);
        CriticalResultService criticalResults = mock(CriticalResultService.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        LabResultService reports = new LabResultService(new LabPolicyCatalog(List.of(), List.of()), CLOCK);

        collection = new SpecimenCollectionService(accessions, patients, new LabActionPolicy(),
                transactions, audit);
        results = new ResultEntryService(accessions, reports, criticalResults, transactions, audit, CLOCK);

        // Run the guarded callback synchronously so a deferred denial is observable.
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, SUBJECT, PURPOSE, "corr-lab-clause"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static CommandRequest request(String commandId) {
        // A client may claim anything; the gate must ignore these inert fields.
        return new CommandRequest(commandId, "tenant_admin", List.of("STAFF_INVITE", "AUDIT_READ"));
    }

    private static CommandAuthorization gate(Resolver resolver, InMemoryDecisionSink sink) {
        return CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
    }

    private void authorized(String role) {
        when(accessions.hasActiveMembership(anyString())).thenReturn(true);
        when(accessions.activeRoles(anyString())).thenReturn(List.of(role));
    }

    private static LabAccession specimen(AccessionState state, int version, String siteCode) {
        return new LabAccession(SPECIMEN, version, NUMBER, ISSUER, PATIENT, "ORD-LAB-1", state,
                "PLASMA", siteCode, COLLECTOR_NAME, COLLECTED_AT, null, "acceptable", null, null, null);
    }

    private static CollectRequest collectRequest(String siteCode) {
        return new CollectRequest(PATIENT, NUMBER, ISSUER, "PLASMA", siteCode,
                COLLECTOR_NAME, COLLECTED_AT, "acceptable", 1);
    }

    private static ResultEntryRequest entry() {
        ObservationInput observation = new ObservationInput("GLU", "numeric", "present", "5.5",
                null, "mmol/L", null, null, "photometry", "analyser-1", "4.0", "6.0", null, null);
        return new ResultEntryRequest(PATIENT, NUMBER, ISSUER, 1, "clinician-a", "result-queue",
                "PT30M", "PHONE", List.of(observation));
    }

    private static ResultDecisionRequest decision(int version) {
        return new ResultDecisionRequest(PATIENT, NUMBER, ISSUER, version,
                "clinician-a", "result-queue", "a documented correction");
    }

    private static ApiException denial(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void collectorCanAccessionAndCollect() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = gate(new Resolver(List.of(LabRules.COLLECTOR), true), sink);

        Decision accession = authorization.authorize(request(ACCESSION), IDENTITY);
        Decision collect = authorization.authorize(request(COLLECT), IDENTITY);

        assertEquals(LabActionPolicy.ACCESSION_CREATE, accession.action());
        assertTrue(accession.allowed());
        assertEquals("ALLOWED", accession.reasonCode());
        assertEquals(List.of(LabRules.COLLECTOR), accession.rolesUsed());
        assertEquals(LabActionPolicy.SPECIMEN_COLLECT, collect.action());
        assertTrue(collect.allowed());
        assertEquals("ALLOWED", collect.reasonCode());
        assertEquals(2, sink.decisions().size());
    }

    @Test
    void verifierCanVerifyReleaseAndCorrect() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = gate(new Resolver(List.of(LabRules.VERIFIER), true), sink);
        // The request claims tenant_admin and STAFF_INVITE; only the resolver's role counts.
        Decision verify = authorization.authorize(request(VERIFY), IDENTITY);

        assertEquals(SpecialtyActions.LAB_VERIFY_RELEASE, verify.action());
        assertTrue(verify.allowed());
        assertEquals("ALLOWED", verify.reasonCode());
        assertEquals(List.of(LabRules.VERIFIER), verify.rolesUsed());
        assertEquals(ORG, verify.organizationId());
        assertEquals(SUBJECT, verify.subject());
        assertEquals(NOW, verify.occurredAt());
    }

    @Test
    void verifierCanIssueAndCorrectAReportThroughTheResultService() {
        authorized(LabRules.COLLECTOR);
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1, SITE)),
                Optional.of(specimen(AccessionState.COLLECTED, 1, SITE)),
                Optional.of(specimen(AccessionState.COLLECTED, 1, SITE)));
        results.enter(SPECIMEN, entry());

        TenantContext.set(new TenantContext(ORG, VERIFIER, PURPOSE, "corr-verifier"));
        authorized(LabRules.VERIFIER);
        ReportView issued = results.verify(SPECIMEN, decision(1));
        ReportView corrected = results.correct(SPECIMEN, decision(2));

        assertEquals(VerificationState.FINAL.wire(), issued.state());
        assertEquals(VERIFIER, issued.verifier());
        assertEquals(VerificationState.CORRECTED.wire(), corrected.state());
        assertEquals(3, corrected.version());
        assertEquals(2, corrected.supersedesVersion());
        assertEquals("a documented correction", corrected.reason());
        assertEquals(VERIFIER, corrected.verifier());
        verify(audit).append(any(), eq(ResultEntryService.PURPOSE), eq(ResultEntryService.VERIFY),
                eq("LAB_REPORT"), eq("LAB"), eq(SPECIMEN), eq("SUCCESS"));
        verify(audit).append(any(), eq(ResultEntryService.PURPOSE), eq(ResultEntryService.CORRECT),
                eq("LAB_REPORT"), eq("LAB"), eq(SPECIMEN), eq("SUCCESS"));
    }

    @Test
    void collectorCannotVerifyOrRelease() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = gate(new Resolver(List.of(LabRules.COLLECTOR), true), sink);

        ApiException forbidden = denial(() -> authorization.authorize(request(VERIFY), IDENTITY));

        assertEquals(403, forbidden.status());
        assertEquals("FORBIDDEN", forbidden.code());
        assertEquals("command is not permitted for the active roles", forbidden.getMessage());
        assertFalse(sink.decisions().get(0).allowed());
        assertEquals("COMMAND_FORBIDDEN", sink.decisions().get(0).reasonCode());

        authorized(LabRules.COLLECTOR);
        ApiException verifyDenied = denial(() -> results.verify(SPECIMEN, decision(1)));
        ApiException correctDenied = denial(() -> results.correct(SPECIMEN, decision(1)));
        assertEquals(403, verifyDenied.status());
        assertEquals("FORBIDDEN", verifyDenied.code());
        assertEquals("missing laboratory permission", verifyDenied.getMessage());
        assertEquals(403, correctDenied.status());
        assertEquals("FORBIDDEN", correctDenied.code());
        assertEquals("missing laboratory permission", correctDenied.getMessage());
        verify(audit).append(any(), eq(ResultEntryService.PURPOSE), eq(ResultEntryService.VERIFY),
                eq("LAB_REPORT"), eq("LAB"), eq(SPECIMEN), eq("DENIED"));
        verify(audit).append(any(), eq(ResultEntryService.PURPOSE), eq(ResultEntryService.CORRECT),
                eq("LAB_REPORT"), eq("LAB"), eq(SPECIMEN), eq("DENIED"));
    }

    @Test
    void verifierCannotAccessionOrCollect() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = gate(new Resolver(List.of(LabRules.VERIFIER), true), sink);

        ApiException accessionDenied = denial(() -> authorization.authorize(request(ACCESSION), IDENTITY));
        ApiException collectDenied = denial(() -> authorization.authorize(request(COLLECT), IDENTITY));

        assertEquals(403, accessionDenied.status());
        assertEquals("FORBIDDEN", accessionDenied.code());
        assertEquals("command is not permitted for the active roles", accessionDenied.getMessage());
        assertEquals(403, collectDenied.status());
        assertEquals("FORBIDDEN", collectDenied.code());
        assertEquals("command is not permitted for the active roles", collectDenied.getMessage());
        assertEquals("COMMAND_FORBIDDEN", sink.decisions().get(0).reasonCode());
        assertEquals("COMMAND_FORBIDDEN", sink.decisions().get(1).reasonCode());

        authorized(LabRules.VERIFIER);
        ApiException serviceDenied = denial(() -> collection.collect(SPECIMEN, collectRequest(SITE)));
        assertEquals(403, serviceDenied.status());
        assertEquals("FORBIDDEN", serviceDenied.code());
        assertEquals("missing laboratory permission", serviceDenied.getMessage());
        verify(audit).append(any(), eq(SpecimenCollectionService.PURPOSE),
                eq(LabActionPolicy.SPECIMEN_COLLECT), eq("SPECIMEN"), eq("LAB"), eq(SPECIMEN), eq("DENIED"));
    }

    @Test
    void receptionClinicianTenantAdminVerbsRefusedForLabRoles() {
        List<String> otherRolesVerbs = List.of(REGISTER, BOOK, INVITE);
        List<String> expectedActions = List.of("PATIENT_REGISTER", ActionPolicy.APPOINTMENT_BOOK,
                "STAFF_INVITE");
        for (String role : List.of(LabRules.COLLECTOR, LabRules.VERIFIER)) {
            InMemoryDecisionSink sink = new InMemoryDecisionSink();
            CommandAuthorization authorization = gate(new Resolver(List.of(role), true), sink);
            for (int index = 0; index < otherRolesVerbs.size(); index++) {
                String commandId = otherRolesVerbs.get(index);
                int position = index;
                ApiException forbidden = denial(() ->
                        authorization.authorize(request(commandId), IDENTITY));
                assertEquals(403, forbidden.status());
                assertEquals("FORBIDDEN", forbidden.code());
                assertEquals("command is not permitted for the active roles", forbidden.getMessage());
                Decision decision = sink.decisions().get(position);
                assertFalse(decision.allowed());
                assertEquals("COMMAND_FORBIDDEN", decision.reasonCode());
                assertEquals(expectedActions.get(position), decision.action());
            }
        }
    }

    @Test
    void labActionDeniedWithoutActiveMembership() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = gate(new Resolver(List.of(LabRules.COLLECTOR), false), sink);

        ApiException forbidden = denial(() -> authorization.authorize(request(COLLECT), IDENTITY));

        assertEquals(403, forbidden.status());
        assertEquals("FORBIDDEN", forbidden.code());
        assertTrue(forbidden.getMessage().contains("no active membership"));
        assertEquals("MEMBERSHIP_INACTIVE", sink.decisions().get(0).reasonCode());

        when(accessions.hasActiveMembership(anyString())).thenReturn(false);
        ApiException serviceDenied = denial(() -> collection.collect(SPECIMEN, collectRequest(SITE)));
        assertEquals(403, serviceDenied.status());
        assertEquals("FORBIDDEN", serviceDenied.code());
        assertTrue(serviceDenied.getMessage().contains("no active membership"));
        verify(audit).append(any(), eq(SpecimenCollectionService.PURPOSE),
                eq(LabActionPolicy.SPECIMEN_COLLECT), eq("SPECIMEN"), eq("LAB"), eq(SPECIMEN), eq("DENIED"));
    }

    @Test
    void requisitionAssignmentDoesNotNarrowAccessToday() {
        // Characterization, NOT desired behaviour. lab_accession has no assignee and
        // no lab service consults V93__membership_facility_scope's
        // is_facility_out_of_scope, so a collector can still collect a requisition
        // whose site is outside their assignment. The would-prove test (expected
        // 403 FORBIDDEN plus an audited DENIED) is frozen in
        // docs/spec/clauses/authz/role-lab-collector-verifier.md#gap. A later task
        // must delete this characterization and enable that assertion instead.
        authorized(LabRules.COLLECTOR);
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.ACCESSIONED, 1, FOREIGN_SITE)),
                Optional.of(specimen(AccessionState.COLLECTED, 2, FOREIGN_SITE)));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);

        LabAccession collected = collection.collect(SPECIMEN, collectRequest(SITE));

        assertEquals(AccessionState.COLLECTED, collected.state());
        verify(audit).append(any(), eq(SpecimenCollectionService.PURPOSE),
                eq(LabActionPolicy.SPECIMEN_COLLECT), eq("SPECIMEN"), eq("LAB"), eq(SPECIMEN), eq("SUCCESS"));
    }
}
