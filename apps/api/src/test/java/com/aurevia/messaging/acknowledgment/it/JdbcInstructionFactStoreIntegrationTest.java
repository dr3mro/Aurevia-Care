package com.aurevia.messaging.acknowledgment.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.messaging.acknowledgment.InstructionFactService;
import com.aurevia.messaging.acknowledgment.InstructionFactStore;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Fact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;
import com.aurevia.messaging.acknowledgment.JdbcInstructionFactStore;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-01 / ARC-02 / OPS-02 acceptance for the PostgreSQL instruction-fact store.
 *
 * <p>Every value is synthetic. Two tenants store identical local identifiers,
 * instruction ids and subjects, so a read or write that escapes tenant scope is
 * observable. Reads and writes bind the tenant through
 * {@link com.aurevia.tenant.TenantContext} exactly as the HTTP
 * interceptor does; V63's forced row-level security is the boundary under test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_instruction_facts_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class JdbcInstructionFactStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String UNIT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String UNIT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    static final String STAFF = "fact-staff";
    static final String PATIENT_SUBJECT = "fact-patient";
    static final String LINKED_ACCOUNT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    static final String CONNECTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    static final String MISSING_ID = "99999999-9999-9999-9999-999999999999";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_instruction_facts_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Autowired
    InstructionFactStore factStore;

    @Autowired
    JdbcInstructionFactStore store;

    /** Create a fresh database, apply all migrations and seed two tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_instruction_facts_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_instruction_facts_it "
                    + "OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedIdentity(statement, ORG_A, "clinician", UNIT_A);
            seedIdentity(statement, ORG_B, "tenant_admin", UNIT_B);
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_instruction_facts_it WITH (FORCE)");
        }
    }

    private static void seedIdentity(Statement statement, String org, String role,
                                     String connectionUnit) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + STAFF + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + STAFF + "', 'staff')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + STAFF + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + STAFF + "'");
        // IAM-05 verified chart link: the authoritative current_chart_links fold.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                + "dob_unknown_reason, created_by) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'SYN-MRN-" + org.substring(0, 2)
                + "', 'SYNTHETIC', 'Synthetic Patient', 'synthetic patient', 'synthetic fixture', "
                + "(SELECT id FROM public.user_identity WHERE subject = '" + STAFF + "'))");
        statement.execute("INSERT INTO public.patient_account (organization_id, id, subject, display_name) "
                + "VALUES ('" + org + "', '" + LINKED_ACCOUNT + "', '" + PATIENT_SUBJECT + "', 'patient')");
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', gen_random_uuid(), '" + LINKED_ACCOUNT + "', '" + PATIENT
                + "', 'linked', 'invitation')");
        // V60 authoritative patient-unit connection.
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, id, patient_id, unit_id, context, context_reference, "
                + "relationship_id, state, verified_by, verified_at) VALUES ('"
                + org + "', '" + CONNECTION + "', '" + PATIENT + "', '" + connectionUnit
                + "', 'care_relationship', 'synthetic-ref', gen_random_uuid(), 'active', '"
                + STAFF + "', '" + NOW + "')");
        // No authoritative staff-to-unit assignment exists; V63 seeds the reference directly.
        statement.execute("INSERT INTO public.instruction_fact_unit_assignment "
                + "(organization_id, subject, unit_id) VALUES ('"
                + org + "', '" + STAFF + "', '" + connectionUnit + "')");
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "INSTRUCTION_FACT", "fact-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void productionBeanIsTheJdbcStore() {
        assertNotNull(store);
        assertTrue(factStore instanceof JdbcInstructionFactStore,
                "the production InstructionFactStore bean must be the PostgreSQL adapter");
    }

    @Test
    void identityLookupsUseAuthoritativeSourcesAndStayTenantScoped() {
        assertTrue(as(ORG_A, STAFF, () -> store.hasActiveMembership(ORG_A, STAFF)));
        assertTrue(as(ORG_B, STAFF, () -> store.hasActiveMembership(ORG_B, STAFF)));
        assertFalse(as(ORG_A, "unknown", () -> store.hasActiveMembership(ORG_A, "unknown")));
        assertEquals(List.of("clinician"), as(ORG_A, STAFF, () -> store.activeRoles(ORG_A, STAFF)));
        assertEquals(List.of("tenant_admin"), as(ORG_B, STAFF, () -> store.activeRoles(ORG_B, STAFF)));
        assertEquals(List.of(), as(ORG_A, "unknown", () -> store.activeRoles(ORG_A, "unknown")));
        assertEquals(Set.of(PATIENT),
                as(ORG_A, PATIENT_SUBJECT, () -> store.verifiedPatientCharts(ORG_A, PATIENT_SUBJECT)));
        assertEquals(Set.of(), as(ORG_A, "unknown", () -> store.verifiedPatientCharts(ORG_A, "unknown")));
        assertTrue(as(ORG_A, STAFF, () -> store.hasActiveConnection(ORG_A, PATIENT, UNIT_A)));
        assertFalse(as(ORG_A, STAFF, () -> store.hasActiveConnection(ORG_A, PATIENT, UNIT_B)));
        assertFalse(as(ORG_B, STAFF, () -> store.hasActiveConnection(ORG_B, PATIENT, UNIT_A)));
        assertTrue(as(ORG_A, STAFF, () -> store.isStaffAssignedToUnit(ORG_A, STAFF, UNIT_A)));
        assertFalse(as(ORG_A, STAFF, () -> store.isStaffAssignedToUnit(ORG_A, STAFF, UNIT_B)));
        assertFalse(as(ORG_B, STAFF, () -> store.isStaffAssignedToUnit(ORG_B, STAFF, UNIT_A)));
    }

    @Test
    void instructionsAndPublicationsRoundTripWithIdenticalLocalIdsAcrossTenants() {
        String id = "01000000-0000-0000-0000-000000000001";
        Instruction a = instruction(ORG_A, id, 1, true, NOW.minusSeconds(1), UNIT_A);
        as(ORG_A, STAFF, () -> store.putInstruction(ORG_A, a));
        assertEquals(a, as(ORG_A, STAFF, () -> store.instruction(ORG_A, id, 1)).orElseThrow());
        assertTrue(as(ORG_A, STAFF, () -> store.instruction(ORG_A, id, 2)).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> as(ORG_A, STAFF, () -> store.putInstruction(ORG_A, null)));
        assertThrows(IllegalArgumentException.class,
                () -> as(ORG_A, STAFF, () -> store.putInstruction(ORG_B, a)));
        Instruction competing = new Instruction(id, ORG_A, PATIENT, UNIT_A, 1, "tampered",
                true, NOW.minusSeconds(1));
        assertEquals(a, as(ORG_A, STAFF, () -> store.putInstruction(ORG_A, competing)),
                "an immutable revision returns the original");
        Instruction second = instruction(ORG_A, id, 2, true, NOW.minusSeconds(1), UNIT_A);
        as(ORG_A, STAFF, () -> store.putInstruction(ORG_A, second));
        assertEquals(2, as(ORG_A, STAFF, () -> store.latestInstruction(ORG_A, id)).orElseThrow().revision());
        assertTrue(as(ORG_A, STAFF, () -> store.latestInstruction(ORG_A, MISSING_ID)).isEmpty());

        Instruction b = instruction(ORG_B, id, 1, true, NOW.minusSeconds(1), UNIT_B);
        as(ORG_B, STAFF, () -> store.putInstruction(ORG_B, b));
        assertEquals(b, as(ORG_B, STAFF, () -> store.instruction(ORG_B, id, 1)).orElseThrow());
        assertTrue(as(ORG_A, STAFF, () -> store.instructionsForUnit(ORG_A, UNIT_A)).isEmpty(),
                "no publication yet");

        Publication publicationA = new Publication(ORG_A, id, 1, STAFF, NOW);
        as(ORG_A, STAFF, () -> store.putPublication(ORG_A, publicationA));
        assertEquals(publicationA, as(ORG_A, STAFF, () -> store.putPublication(ORG_A,
                new Publication(ORG_A, id, 1, "other", NOW.plusSeconds(1)))));
        assertEquals(List.of(publicationA), as(ORG_A, STAFF, () -> store.publications(ORG_A, id, 1)));
        assertTrue(as(ORG_A, STAFF, () -> store.publications(ORG_A, id, 9)).isEmpty());
        assertTrue(as(ORG_B, STAFF, () -> store.publications(ORG_B, id, 1)).isEmpty());
        assertTrue(as(ORG_A, STAFF, () -> store.instructionsForUnit(ORG_A, UNIT_A)).stream()
                .anyMatch(item -> item.id().equals(id)));
        assertTrue(as(ORG_A, STAFF, () -> store.instructionsForUnit(ORG_A, UNIT_B)).isEmpty());
        assertTrue(as(ORG_B, STAFF, () -> store.instructionsForUnit(ORG_B, UNIT_A)).isEmpty());
    }

    @Test
    void dispatchFirstViewAndAcknowledgmentFactsAreAppendOnlyAndTenantScoped() {
        String id = "02000000-0000-0000-0000-000000000002";
        Dispatch dispatch = new Dispatch(ORG_A, id, 1, "NEW_INSTRUCTION", "corr-a", NOW);
        as(ORG_A, STAFF, () -> store.appendDispatch(ORG_A, dispatch));
        assertEquals(List.of(dispatch), as(ORG_A, STAFF, () -> store.dispatches(ORG_A, id, 1)));
        assertTrue(as(ORG_A, STAFF, () -> store.dispatches(ORG_A, id, 2)).isEmpty());
        assertTrue(as(ORG_B, STAFF, () -> store.dispatches(ORG_B, id, 1)).isEmpty());

        FirstView view = new FirstView(ORG_A, id, 1, PATIENT_SUBJECT, NOW);
        assertEquals(view, as(ORG_A, STAFF, () -> store.putFirstViewIfAbsent(ORG_A, view)));
        assertEquals(view, as(ORG_A, STAFF, () -> store.putFirstViewIfAbsent(ORG_A,
                new FirstView(ORG_A, id, 1, PATIENT_SUBJECT, NOW.plusSeconds(1)))));
        assertEquals(List.of(view),
                as(ORG_A, STAFF, () -> store.firstViews(ORG_A, id, 1, PATIENT_SUBJECT)));
        assertTrue(as(ORG_A, STAFF, () -> store.firstViews(ORG_A, id, 1, "other")).isEmpty());
        assertTrue(as(ORG_B, STAFF, () -> store.firstViews(ORG_B, id, 1, PATIENT_SUBJECT)).isEmpty());

        Acknowledgment first = new Acknowledgment(ORG_A, id, 1, "account-a",
                AcknowledgmentScope.RECEIPT, NOW);
        Acknowledgment later = new Acknowledgment(ORG_A, id, 1, "account-b",
                AcknowledgmentScope.RECEIPT, NOW.plusSeconds(1));
        assertEquals(first, as(ORG_A, STAFF, () -> store.putAcknowledgmentIfAbsent(ORG_A, first)));
        assertEquals(first, as(ORG_A, STAFF, () -> store.putAcknowledgmentIfAbsent(ORG_A,
                new Acknowledgment(ORG_A, id, 1, "account-a", AcknowledgmentScope.RECEIPT,
                        NOW.plusSeconds(9)))));
        assertEquals(later, as(ORG_A, STAFF, () -> store.putAcknowledgmentIfAbsent(ORG_A, later)));
        assertEquals(List.of(first, later),
                as(ORG_A, STAFF, () -> store.acknowledgments(ORG_A, id, 1)));
        assertTrue(as(ORG_A, STAFF, () -> store.acknowledgments(ORG_A, id, 2)).isEmpty());
        assertTrue(as(ORG_B, STAFF, () -> store.acknowledgments(ORG_B, id, 1)).isEmpty());
    }

    @Test
    void followUpsRoundTripIncludingResolutionAndTenantIsolation() {
        String id = "03000000-0000-0000-0000-000000000003";
        FollowUp routed = new FollowUp(ORG_A, id, 1, UNIT_A, NOW, FollowUpState.ROUTED,
                null, null, null);
        assertEquals(routed, as(ORG_A, STAFF, () -> store.putFollowUpIfAbsent(ORG_A, routed)));
        assertEquals(routed, as(ORG_A, STAFF, () -> store.putFollowUpIfAbsent(ORG_A,
                new FollowUp(ORG_A, id, 1, UNIT_B, NOW.plusSeconds(1), FollowUpState.OPEN,
                        null, null, null))));
        assertEquals(routed, as(ORG_A, STAFF, () -> store.followUps(ORG_A, id, 1)).orElseThrow());
        assertTrue(as(ORG_A, STAFF, () -> store.followUps(ORG_A, id, 2)).isEmpty());
        FollowUp resolved = as(ORG_A, STAFF, () -> store.resolveFollowUp(ORG_A, id, 1,
                STAFF, "owner contacted", NOW.plusSeconds(5)));
        assertEquals(FollowUpState.RESOLVED, resolved.state());
        assertEquals(STAFF, resolved.resolvedBy());
        assertEquals("owner contacted", resolved.reason());
        assertEquals(NOW.plusSeconds(5), resolved.resolvedAt());
        assertEquals(resolved, as(ORG_A, STAFF, () -> store.followUps(ORG_A, id, 1)).orElseThrow());
        String seededResolved = "03000000-0000-0000-0000-000000000009";
        FollowUp preResolved = new FollowUp(ORG_A, seededResolved, 3, UNIT_A, NOW,
                FollowUpState.RESOLVED, STAFF, NOW.plusSeconds(10), "pre-resolved");
        assertEquals(preResolved,
                as(ORG_A, STAFF, () -> store.putFollowUpIfAbsent(ORG_A, preResolved)));
        assertEquals(preResolved,
                as(ORG_A, STAFF, () -> store.followUps(ORG_A, seededResolved, 3)).orElseThrow());
        assertThrows(IllegalStateException.class,
                () -> as(ORG_A, STAFF, () -> store.resolveFollowUp(ORG_A, MISSING_ID, 1,
                        STAFF, "x", NOW)));

        FollowUp other = new FollowUp(ORG_B, id, 1, UNIT_B, NOW, FollowUpState.ROUTED,
                null, null, null);
        as(ORG_B, STAFF, () -> store.putFollowUpIfAbsent(ORG_B, other));
        assertEquals(other, as(ORG_B, STAFF, () -> store.followUps(ORG_B, id, 1)).orElseThrow());
        assertTrue(as(ORG_A, STAFF, () -> store.allFollowUps(ORG_A)).stream()
                .anyMatch(item -> item.instructionId().equals(id)));
        assertTrue(as(ORG_B, STAFF, () -> store.allFollowUps(ORG_B)).stream()
                .anyMatch(item -> item.instructionId().equals(id)));
        assertTrue(as(ORG_A, STAFF, () -> store.allFollowUps(ORG_A)).stream()
                .allMatch(item -> ORG_A.equals(item.organizationId())));
    }

    @Test
    void overdueCandidatesRespectEveryFilterAndTenantScope() {
        String overdue = "04000000-0000-0000-0000-000000000004";
        String noDue = "04000000-0000-0000-0000-000000000005";
        String future = "04000000-0000-0000-0000-000000000006";
        String notRequested = "04000000-0000-0000-0000-000000000007";
        as(ORG_A, STAFF, () -> {
            store.putInstruction(ORG_A, instruction(ORG_A, overdue, 1, true, NOW.minusSeconds(1), UNIT_A));
            store.putInstruction(ORG_A, instruction(ORG_A, noDue, 1, true, null, UNIT_A));
            store.putInstruction(ORG_A, instruction(ORG_A, future, 1, true, NOW.plusSeconds(60), UNIT_A));
            store.putInstruction(ORG_A, instruction(ORG_A, notRequested, 1, false, NOW.minusSeconds(1), UNIT_A));
            return null;
        });
        as(ORG_B, STAFF, () -> {
            store.putInstruction(ORG_B, instruction(ORG_B, overdue, 1, true, NOW.minusSeconds(1), UNIT_B));
            return null;
        });
        assertTrue(as(ORG_A, STAFF, () -> store.overdueCandidates(ORG_A, NOW)).stream()
                .anyMatch(item -> item.id().equals(overdue)));
        assertTrue(as(ORG_B, STAFF, () -> store.overdueCandidates(ORG_B, NOW)).stream()
                .anyMatch(item -> item.id().equals(overdue)));

        as(ORG_A, STAFF, () -> store.putAcknowledgmentIfAbsent(ORG_A, new Acknowledgment(ORG_A,
                overdue, 1, "account-a", AcknowledgmentScope.RECEIPT, NOW)));
        assertTrue(as(ORG_A, STAFF, () -> store.overdueCandidates(ORG_A, NOW)).stream()
                .noneMatch(item -> item.id().equals(overdue)));
        // Only the latest revision can be an overdue candidate.
        as(ORG_A, STAFF, () -> {
            store.putInstruction(ORG_A, instruction(ORG_A, overdue, 2, true, NOW.minusSeconds(1), UNIT_A));
            return null;
        });
        assertEquals(2, as(ORG_A, STAFF, () -> store.overdueCandidates(ORG_A, NOW)).stream()
                .filter(item -> item.id().equals(overdue)).findFirst().orElseThrow().revision());
        as(ORG_A, STAFF, () -> store.putAcknowledgmentIfAbsent(ORG_A, new Acknowledgment(ORG_A,
                overdue, 2, "account-a", AcknowledgmentScope.RECEIPT, NOW)));
        assertTrue(as(ORG_A, STAFF, () -> store.overdueCandidates(ORG_A, NOW)).stream()
                .noneMatch(item -> item.id().equals(overdue)));
        String withFollowUp = "04000000-0000-0000-0000-000000000008";
        as(ORG_A, STAFF, () -> {
            store.putInstruction(ORG_A, instruction(ORG_A, withFollowUp, 1, true, NOW.minusSeconds(1), UNIT_A));
            store.putFollowUpIfAbsent(ORG_A, new FollowUp(ORG_A, withFollowUp, 1, UNIT_A, NOW,
                    FollowUpState.ROUTED, null, null, null));
            return null;
        });
        assertTrue(as(ORG_A, STAFF, () -> store.overdueCandidates(ORG_A, NOW)).stream()
                .noneMatch(item -> item.id().equals(withFollowUp)));
    }

    @Test
    void clientDigestsAreDurableAndScoped() {
        assertTrue(as(ORG_A, STAFF, () -> store.putClientDigest(ORG_A, STAFF, "PUBLISH", "key-1", "digest-1")));
        assertFalse(as(ORG_A, STAFF, () -> store.putClientDigest(ORG_A, STAFF, "PUBLISH", "key-1", "digest-2")));
        assertEquals("digest-1",
                as(ORG_A, STAFF, () -> store.clientDigest(ORG_A, STAFF, "PUBLISH", "key-1")).orElseThrow());
        assertTrue(as(ORG_A, STAFF, () -> store.clientDigest(ORG_A, STAFF, "PUBLISH", "key-2")).isEmpty());
        assertTrue(as(ORG_A, STAFF, () -> store.putClientDigest(ORG_A, STAFF, "DISPATCH", "key-1", "digest-3")));
        assertTrue(as(ORG_B, STAFF, () -> store.putClientDigest(ORG_B, STAFF, "PUBLISH", "key-1", "digest-4")));
        assertEquals(1, as(ORG_A, STAFF, () -> store.clientDigestCount(ORG_A, STAFF, "PUBLISH")));
        assertEquals(1, as(ORG_A, STAFF, () -> store.clientDigestCount(ORG_A, STAFF, "DISPATCH")));
        assertEquals(1, as(ORG_B, STAFF, () -> store.clientDigestCount(ORG_B, STAFF, "PUBLISH")));
        assertEquals(0, as(ORG_A, STAFF, () -> store.clientDigestCount(ORG_A, "unknown", "PUBLISH")));
    }

    @Test
    void auditFactsAndAllFactsAreTenantScoped() throws Exception {
        String id = "06000000-0000-0000-0000-000000000006";
        AuditFact first = new AuditFact(ORG_A, STAFF, "PUBLISH", id, "SUCCESS",
                InstructionFactService.PURPOSE, InstructionFactService.SCOPE, "corr-a", NOW);
        AuditFact second = new AuditFact(ORG_A, STAFF, "ACKNOWLEDGE", id, "SUCCESS",
                InstructionFactService.PURPOSE, InstructionFactService.SCOPE, "corr-b", NOW.plusSeconds(1));
        as(ORG_A, STAFF, () -> {
            store.appendAudit(first);
            store.appendAudit(second);
            return null;
        });
        as(ORG_B, STAFF, () -> {
            store.appendAudit(new AuditFact(ORG_B, STAFF, "PUBLISH", id, "SUCCESS",
                    InstructionFactService.PURPOSE, InstructionFactService.SCOPE, "corr-c", NOW));
            return null;
        });
        List<AuditFact> auditsA = as(ORG_A, STAFF, () -> store.auditEvents(ORG_A));
        assertTrue(auditsA.containsAll(List.of(first, second)));
        assertTrue(auditsA.indexOf(first) < auditsA.indexOf(second));
        assertEquals(1, as(ORG_B, STAFF, () -> store.auditEvents(ORG_B)).size());
        assertTrue(auditsA.stream().allMatch(fact -> ORG_A.equals(fact.organizationId())));

        as(ORG_A, STAFF, () -> {
            store.putPublication(ORG_A, new Publication(ORG_A, id, 1, STAFF, NOW));
            store.appendDispatch(ORG_A, new Dispatch(ORG_A, id, 1, "NEW_INSTRUCTION", "corr-a", NOW));
            store.putFirstViewIfAbsent(ORG_A, new FirstView(ORG_A, id, 1, PATIENT_SUBJECT, NOW));
            store.putAcknowledgmentIfAbsent(ORG_A, new Acknowledgment(ORG_A, id, 1, "account-a",
                    AcknowledgmentScope.RECEIPT, NOW));
            store.putFollowUpIfAbsent(ORG_A, new FollowUp(ORG_A, id, 1, UNIT_A, NOW,
                    FollowUpState.ROUTED, null, null, null));
            return null;
        });
        List<Fact> facts = as(ORG_A, STAFF, () -> store.allFacts(ORG_A, id));
        assertEquals(5, facts.size());
        assertEquals(5, facts.stream().map(Fact::type).distinct().count());
        assertTrue(as(ORG_A, STAFF, () -> store.allFacts(ORG_A, MISSING_ID)).isEmpty());
        assertTrue(as(ORG_B, STAFF, () -> store.allFacts(ORG_B, id)).isEmpty());
    }

    @Test
    void appendOnlyFactsAreImmutableForTheMigrationOwnerToo() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.instruction_fact_instruction "
                    + "(organization_id, id, revision, patient_id, unit_id, content_ref, "
                    + "requires_acknowledgment, acknowledgment_due_at) VALUES ('"
                    + ORG_A + "', '07000000-0000-0000-0000-000000000007', 1, '" + PATIENT + "', '"
                    + UNIT_A + "', 'artifact', true, '" + NOW + "')");
            statement.execute("INSERT INTO public.instruction_fact_audit "
                    + "(organization_id, subject, action, resource_id, outcome, occurred_at) VALUES ('"
                    + ORG_A + "', '" + STAFF + "', 'PUBLISH', 'resource', 'SUCCESS', '" + NOW + "')");
            assertImmutable(statement, "instruction",
                    "UPDATE public.instruction_fact_instruction SET content_ref = 'changed'");
            assertImmutable(statement, "audit",
                    "UPDATE public.instruction_fact_audit SET outcome = 'DENIED'");
        }
    }

    private static Instruction instruction(String organizationId, String id, int revision,
                                           boolean requiresAck, Instant dueAt, String unitId) {
        return new Instruction(id, organizationId, PATIENT, unitId, revision,
                "artifact-" + revision, requiresAck, dueAt);
    }

    private static void assertImmutable(Statement statement, String fact, String update) {
        assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(update),
                fact + " must reject an UPDATE for every role, including the owner");
    }
}
