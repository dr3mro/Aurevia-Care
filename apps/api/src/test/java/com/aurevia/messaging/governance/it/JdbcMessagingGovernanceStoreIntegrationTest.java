package com.aurevia.messaging.governance.it;

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

import com.aurevia.messaging.governance.JdbcMessagingGovernanceStore;
import com.aurevia.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.messaging.governance.MessagingAuditContract.ConnectionReference;
import com.aurevia.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.MessagingResource;
import com.aurevia.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.messaging.governance.MessagingAuditContract.RecordAccessPolicy;
import com.aurevia.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDuty;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.messaging.governance.MessagingGovernanceStore;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-01 / ARC-02 / OPS-02 acceptance for the PostgreSQL messaging governance
 * store.
 *
 * <p>Every value is synthetic. Two tenants store identical local identifiers,
 * subjects, resource ids, connection ids and message ids, so a read or write
 * that escapes tenant scope is observable. Reads and writes bind the tenant
 * through {@link com.aurevia.tenant.TenantContext} exactly as the
 * HTTP interceptor does; V62's forced row-level security is the isolation
 * boundary under test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_governance_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class JdbcMessagingGovernanceStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String SHARED_SUBJECT = "governance-shared";
    static final String INACTIVE_SUBJECT = "governance-inactive";
    static final String SHARED_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String OTHER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String IMMUTABLE_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_governance_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Autowired
    MessagingGovernanceStore governanceStore;

    @Autowired
    JdbcMessagingGovernanceStore store;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_governance_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_governance_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, true, "tenant_admin", "conversation", "observation");
            seedTenant(statement, ORG_B, true, "privacy_auditor", "conversation", "observation");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_governance_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, boolean sharedActive, String role,
                                   String firstType, String secondType) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SHARED_SUBJECT + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + SHARED_SUBJECT + "', 'shared')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + INACTIVE_SUBJECT + "', 'inactive')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, " + (sharedActive ? "'active'" : "'revoked'")
                + " FROM public.user_identity WHERE subject = '" + SHARED_SUBJECT + "'");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'revoked' FROM public.user_identity WHERE subject = '"
                + INACTIVE_SUBJECT + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + SHARED_SUBJECT + "'");
        // The governance interface has no resource-write method: tracked
        // resources are an authorized reference populated by the owning workflow.
        statement.execute("INSERT INTO public.messaging_resource "
                + "(organization_id, id, resource_type, created_at) VALUES ('"
                + org + "', '" + SHARED_ID + "', '" + firstType + "', '" + NOW.minus(java.time.Duration.ofDays(40))
                + "')");
        statement.execute("INSERT INTO public.messaging_resource "
                + "(organization_id, id, resource_type, created_at) VALUES ('"
                + org + "', '" + OTHER_ID + "', '" + secondType + "', '" + NOW
                + "')");
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "MSG_GOVERNANCE", "governance-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void productionBeanIsTheJdbcStore() {
        assertNotNull(store);
        assertTrue(governanceStore instanceof JdbcMessagingGovernanceStore,
                "the production MessagingGovernanceStore bean must be the PostgreSQL adapter");
    }

    @Test
    void membershipAndRolesComeFromTheAuthoritativeFold() {
        assertTrue(as(ORG_A, SHARED_SUBJECT,
                () -> store.hasActiveMembership(ORG_A, SHARED_SUBJECT)));
        assertEquals(List.of("tenant_admin"),
                as(ORG_A, SHARED_SUBJECT, () -> store.activeRoles(ORG_A, SHARED_SUBJECT)));
        assertTrue(as(ORG_B, SHARED_SUBJECT,
                () -> store.hasActiveMembership(ORG_B, SHARED_SUBJECT)));
        assertEquals(List.of("privacy_auditor"),
                as(ORG_B, SHARED_SUBJECT, () -> store.activeRoles(ORG_B, SHARED_SUBJECT)),
                "the same subject holds a different role in the other tenant");
        assertFalse(as(ORG_A, INACTIVE_SUBJECT,
                () -> store.hasActiveMembership(ORG_A, INACTIVE_SUBJECT)));
        assertEquals(List.of(), as(ORG_A, INACTIVE_SUBJECT,
                () -> store.activeRoles(ORG_A, INACTIVE_SUBJECT)));
        assertFalse(as(ORG_A, "unknown-subject",
                () -> store.hasActiveMembership(ORG_A, "unknown-subject")));
        assertEquals(List.of(), as(ORG_A, "unknown-subject",
                () -> store.activeRoles(ORG_A, "unknown-subject")));
    }

    @Test
    void retentionPoliciesRoundTripWithIdenticalLocalKeysButDistinctTenants() {
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putRetentionPolicy(ORG_A, new RetentionPolicy(ORG_A, "conversation", 30L));
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putRetentionPolicy(ORG_B, new RetentionPolicy(ORG_B, "conversation", 90L));
            return null;
        });
        assertEquals(new RetentionPolicy(ORG_A, "conversation", 30L),
                as(ORG_A, SHARED_SUBJECT, () -> store.retentionPolicy(ORG_A, "conversation")).orElseThrow());
        assertEquals(new RetentionPolicy(ORG_B, "conversation", 90L),
                as(ORG_B, SHARED_SUBJECT, () -> store.retentionPolicy(ORG_B, "conversation")).orElseThrow());
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.retentionPolicy(ORG_A, "missing")).isEmpty(),
                "an unapproved resource family has no policy");
        assertThrows(Exception.class, () -> as(ORG_A, SHARED_SUBJECT, () -> {
            store.putRetentionPolicy(ORG_B, new RetentionPolicy(ORG_B, "conversation", 1L));
            return null;
        }), "WITH CHECK rejects a row whose organization_id is not the bound tenant");
    }

    @Test
    void resourcesAndRetentionDutiesAreTenantScoped() {
        List<MessagingResource> a = as(ORG_A, SHARED_SUBJECT, () -> store.resources(ORG_A));
        List<MessagingResource> b = as(ORG_B, SHARED_SUBJECT, () -> store.resources(ORG_B));
        assertEquals(2, a.size());
        assertEquals(2, b.size());
        assertTrue(a.stream().allMatch(resource -> ORG_A.equals(resource.organizationId())));
        assertTrue(b.stream().allMatch(resource -> ORG_B.equals(resource.organizationId())));
        assertEquals(SHARED_ID, a.get(0).id());
        assertEquals("conversation", a.get(0).resourceType());
        assertEquals(NOW.minus(java.time.Duration.ofDays(40)), a.get(0).createdAt());
        assertEquals(OTHER_ID, b.get(1).id());

        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putRetentionDuty(ORG_A, new RetentionDuty(ORG_A, SHARED_ID, 10L, 20L));
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putRetentionDuty(ORG_B, new RetentionDuty(ORG_B, SHARED_ID, 30L, 40L));
            return null;
        });
        assertEquals(new RetentionDuty(ORG_A, SHARED_ID, 10L, 20L),
                as(ORG_A, SHARED_SUBJECT, () -> store.retentionDuty(ORG_A, SHARED_ID)).orElseThrow());
        assertEquals(new RetentionDuty(ORG_B, SHARED_ID, 30L, 40L),
                as(ORG_B, SHARED_SUBJECT, () -> store.retentionDuty(ORG_B, SHARED_ID)).orElseThrow());
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.retentionDuty(ORG_A, OTHER_ID)).isEmpty());
    }

    @Test
    void legalHoldsRoundTripIncludingReleaseAndTenantScope() {
        LegalHold activeA = new LegalHold(SHARED_ID, ORG_A, OTHER_ID, "review", "clinician-a",
                NOW, null, null, null);
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putLegalHold(ORG_A, activeA);
            return null;
        });
        assertEquals(activeA, as(ORG_A, SHARED_SUBJECT, () -> store.legalHold(ORG_A, SHARED_ID)).orElseThrow());
        assertEquals(List.of(activeA), as(ORG_A, SHARED_SUBJECT, () -> store.legalHolds(ORG_A)));

        LegalHold releasedA = new LegalHold(SHARED_ID, ORG_A, OTHER_ID, "review", "clinician-a",
                NOW, "clinician-b", NOW.plusSeconds(60), "released");
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putLegalHold(ORG_A, releasedA);
            return null;
        });
        assertEquals(releasedA, as(ORG_A, SHARED_SUBJECT, () -> store.legalHold(ORG_A, SHARED_ID)).orElseThrow());
        assertFalse(as(ORG_A, SHARED_SUBJECT, () -> store.legalHold(ORG_A, SHARED_ID)).orElseThrow().active());

        LegalHold holdB = new LegalHold(SHARED_ID, ORG_B, OTHER_ID, "other-review", "clinician-b",
                NOW.plusSeconds(1), null, null, null);
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putLegalHold(ORG_B, holdB);
            return null;
        });
        assertEquals(holdB, as(ORG_B, SHARED_SUBJECT, () -> store.legalHold(ORG_B, SHARED_ID)).orElseThrow());
        assertEquals(List.of(releasedA), as(ORG_A, SHARED_SUBJECT, () -> store.legalHolds(ORG_A)));
        assertEquals(List.of(holdB), as(ORG_B, SHARED_SUBJECT, () -> store.legalHolds(ORG_B)));
    }

    @Test
    void recordAccessPolicyFailsClosedThenRoundTrips() {
        assertEquals(new RecordAccessPolicy(ORG_A, false, "UNKNOWN"),
                as(ORG_A, SHARED_SUBJECT, () -> store.recordAccessPolicy(ORG_A)));
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putRecordAccessPolicy(ORG_A, new RecordAccessPolicy(ORG_A, true, "policy-v1"));
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putRecordAccessPolicy(ORG_B, new RecordAccessPolicy(ORG_B, false, "policy-v2"));
            return null;
        });
        assertEquals(new RecordAccessPolicy(ORG_A, true, "policy-v1"),
                as(ORG_A, SHARED_SUBJECT, () -> store.recordAccessPolicy(ORG_A)));
        assertEquals(new RecordAccessPolicy(ORG_B, false, "policy-v2"),
                as(ORG_B, SHARED_SUBJECT, () -> store.recordAccessPolicy(ORG_B)));
    }

    @Test
    void connectionsRoundTripIncludingNullableRevocationAndTenantScope() {
        ConnectionReference activeA = new ConnectionReference(SHARED_ID, ORG_A, "unit-a", "patient-a", null);
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.addConnection(ORG_A, activeA);
            return null;
        });
        assertEquals(activeA, as(ORG_A, SHARED_SUBJECT, () -> store.connection(ORG_A, SHARED_ID)).orElseThrow());
        assertEquals(List.of(activeA), as(ORG_A, SHARED_SUBJECT, () -> store.allConnections(ORG_A)));

        ConnectionReference revokedB = new ConnectionReference(SHARED_ID, ORG_B, "unit-b", "patient-b", NOW);
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.addConnection(ORG_B, revokedB);
            return null;
        });
        assertEquals(revokedB, as(ORG_B, SHARED_SUBJECT, () -> store.connection(ORG_B, SHARED_ID)).orElseThrow());
        assertEquals(List.of(revokedB), as(ORG_B, SHARED_SUBJECT, () -> store.allConnections(ORG_B)));
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.connection(ORG_A, OTHER_ID)).isEmpty());
        assertThrows(Exception.class, () -> as(ORG_A, SHARED_SUBJECT, () -> {
            store.addConnection(ORG_B, new ConnectionReference(OTHER_ID, ORG_B, "unit-b", "patient-b", null));
            return null;
        }), "WITH CHECK rejects a cross-tenant connection write");
    }

    @Test
    void auditFactsAreOrderedTenantScopedAndImmutable() throws Exception {
        AuditEvent first = audit(SHARED_ID, ORG_A, SHARED_ID, AuditAction.OPEN, NOW);
        AuditEvent second = audit(OTHER_ID, ORG_A, OTHER_ID, AuditAction.DOWNLOAD, NOW.plusSeconds(1));
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.appendAudit(first);
            store.appendAudit(second);
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.appendAudit(audit(SHARED_ID, ORG_B, SHARED_ID, AuditAction.OPEN, NOW));
            return null;
        });

        List<AuditEvent> a = as(ORG_A, SHARED_SUBJECT, () -> store.auditEvents(ORG_A));
        List<AuditEvent> b = as(ORG_B, SHARED_SUBJECT, () -> store.auditEvents(ORG_B));
        assertTrue(a.containsAll(List.of(first, second)));
        assertEquals(1, b.size());
        assertTrue(a.stream().allMatch(event -> ORG_A.equals(event.organizationId())));
        assertTrue(b.stream().allMatch(event -> ORG_B.equals(event.organizationId())));
        assertTrue(a.indexOf(first) < a.indexOf(second), "audit facts are read in insertion order");
        assertEquals(List.of(first), as(ORG_A, SHARED_SUBJECT, () -> store.auditEvents(ORG_A, SHARED_ID)));
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.auditEvents(ORG_A, "missing")).isEmpty());

        assertThrows(Exception.class, () -> as(ORG_A, SHARED_SUBJECT, () -> {
            store.appendAudit(audit(SHARED_ID, ORG_B, SHARED_ID, AuditAction.OPEN, NOW));
            return null;
        }), "WITH CHECK rejects a cross-tenant audit write");
    }

    @Test
    void appendOnlyFactsAreImmutableForTheMigrationOwnerToo() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.messaging_audit_event "
                    + "(organization_id, id, subject, action, resource_id, outcome, occurred_at) VALUES ('"
                    + ORG_A + "', '" + IMMUTABLE_ID + "', '" + SHARED_SUBJECT
                    + "', 'open', 'resource', 'success', '" + NOW + "')");
            statement.execute("INSERT INTO public.messaging_provenance_link "
                    + "(organization_id, id, conversation_id, message_id, patient_id, chart_id, source_type, "
                    + "source_reference, revision, linked_by, linked_at) VALUES ('"
                    + ORG_A + "', '" + IMMUTABLE_ID + "', 'c', 'm', 'p', 'chart', 'message', 'ref', "
                    + "'rev', 'actor', '" + NOW + "')");
            statement.execute("INSERT INTO public.messaging_copy_request "
                    + "(organization_id, id, resource_id, requested_by, purpose, status, created_at) VALUES ('"
                    + ORG_A + "', '" + IMMUTABLE_ID + "', 'resource', 'actor', 'care', 'ROUTED', '" + NOW + "')");
            statement.execute("INSERT INTO public.messaging_revocation_effect "
                    + "(organization_id, id, connection_id, messaging_blocked, attachment_access_blocked, "
                    + "prior_conversations_readable, record_access_basis, recorded_at) VALUES ('"
                    + ORG_A + "', '" + IMMUTABLE_ID + "', 'connection', true, true, false, 'policy-v1', '"
                    + NOW + "')");
            statement.execute("INSERT INTO public.messaging_export "
                    + "(organization_id, id, scope, created_by, created_at, expires_at) VALUES ('"
                    + ORG_A + "', '" + IMMUTABLE_ID + "', 'scope', 'actor', '" + NOW + "', '"
                    + NOW.plusSeconds(60) + "')");

            assertImmutable(statement, "audit event",
                    "UPDATE public.messaging_audit_event SET outcome = 'DENIED'");
            assertImmutable(statement, "provenance link",
                    "UPDATE public.messaging_provenance_link SET revision = 'changed'");
            assertImmutable(statement, "copy request",
                    "UPDATE public.messaging_copy_request SET status = 'changed'");
            assertImmutable(statement, "revocation effect",
                    "UPDATE public.messaging_revocation_effect SET record_access_basis = 'changed'");
            assertImmutable(statement, "export",
                    "UPDATE public.messaging_export SET scope = 'changed'");
        }
    }

    @Test
    void provenanceCopyRequestsRevocationEffectsAndExportsRoundTrip() {
        ProvenanceLink linkA = new ProvenanceLink(SHARED_ID, ORG_A, OTHER_ID, SHARED_ID,
                "patient-a", "chart-a", "message", "ref-a", "rev-a", "clinician-a", NOW);
        ProvenanceLink linkB = new ProvenanceLink(SHARED_ID, ORG_B, OTHER_ID, SHARED_ID,
                "patient-b", "chart-b", "instruction", "ref-b", "rev-b", "clinician-b", NOW.plusSeconds(1));
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putProvenanceLink(ORG_A, linkA);
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putProvenanceLink(ORG_B, linkB);
            return null;
        });
        assertEquals(List.of(linkA), as(ORG_A, SHARED_SUBJECT, () -> store.provenanceLinks(ORG_A, SHARED_ID)));
        assertEquals(List.of(linkB), as(ORG_B, SHARED_SUBJECT, () -> store.provenanceLinks(ORG_B, SHARED_ID)));
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.provenanceLinks(ORG_A, OTHER_ID)).isEmpty());

        CopyRequest copyA = new CopyRequest(SHARED_ID, ORG_A, OTHER_ID, "clinician-a", "care", "ROUTED", NOW);
        CopyRequest copyB = new CopyRequest(SHARED_ID, ORG_B, OTHER_ID, "clinician-b", "care", "ROUTED", NOW);
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putCopyRequest(ORG_A, copyA);
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putCopyRequest(ORG_B, copyB);
            return null;
        });
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.copyRequests(ORG_A)).contains(copyA));
        assertTrue(as(ORG_B, SHARED_SUBJECT, () -> store.copyRequests(ORG_B)).contains(copyB));

        RevocationEffect effectA = new RevocationEffect(SHARED_ID, ORG_A, OTHER_ID,
                true, true, false, "policy-v1", NOW);
        RevocationEffect effectB = new RevocationEffect(SHARED_ID, ORG_B, OTHER_ID,
                true, false, true, "policy-v2", NOW.plusSeconds(1));
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putRevocationEffect(ORG_A, effectA);
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putRevocationEffect(ORG_B, effectB);
            return null;
        });
        assertEquals(List.of(effectA), as(ORG_A, SHARED_SUBJECT, () -> store.revocationEffects(ORG_A, OTHER_ID)));
        assertEquals(List.of(effectB), as(ORG_B, SHARED_SUBJECT, () -> store.revocationEffects(ORG_B, OTHER_ID)));
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.revocationEffects(ORG_A, SHARED_ID)).isEmpty());

        ExportProvenance provenanceA = new ExportProvenance(SHARED_ID, "rev-1", "record_access");
        ExportProvenance provenanceB = new ExportProvenance(SHARED_ID, "rev-2", "legal_review");
        ExportRecord exportA = new ExportRecord(SHARED_ID, ORG_A, "scope-a", "clinician-a", NOW,
                NOW.plusSeconds(60), provenanceA);
        ExportRecord exportB = new ExportRecord(SHARED_ID, ORG_B, "scope-b", "clinician-b", NOW,
                NOW.plusSeconds(120), provenanceB);
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.putExport(ORG_A, exportA);
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.putExport(ORG_B, exportB);
            return null;
        });
        assertEquals(exportA, as(ORG_A, SHARED_SUBJECT, () -> store.export(ORG_A, SHARED_ID)).orElseThrow());
        assertEquals(exportB, as(ORG_B, SHARED_SUBJECT, () -> store.export(ORG_B, SHARED_ID)).orElseThrow());
        assertEquals(provenanceA,
                as(ORG_A, SHARED_SUBJECT, () -> store.export(ORG_A, SHARED_ID)).orElseThrow().provenance());
        assertEquals(provenanceB,
                as(ORG_B, SHARED_SUBJECT, () -> store.export(ORG_B, SHARED_ID)).orElseThrow().provenance());
        assertTrue(as(ORG_A, SHARED_SUBJECT, () -> store.export(ORG_A, OTHER_ID)).isEmpty());
    }

    private static AuditEvent audit(String id, String org, String resourceId, AuditAction action, Instant at) {
        return new AuditEvent(id, org, SHARED_SUBJECT, "MSG_GOVERNANCE", action, resourceId,
                "unit-a", "governance-it", Outcome.SUCCESS, at);
    }

    private static void assertImmutable(Statement statement, String fact, String update) {
        assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(update),
                fact + " must reject an UPDATE for every role, including the owner");
    }
}
