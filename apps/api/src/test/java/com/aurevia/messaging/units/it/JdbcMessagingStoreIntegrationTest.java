package com.aurevia.messaging.units.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

import com.aurevia.messaging.units.JdbcMessagingStore;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.messaging.units.MessagingStore;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-01 / ARC-02 / OPS-02 acceptance for the PostgreSQL care-unit store.
 *
 * <p>Every value is synthetic. Two tenants share identical local unit, patient,
 * relationship and connection identifiers, so a read that escapes tenant scope
 * is observable. Reads and writes bind the tenant through
 * {@link com.aurevia.tenant.TenantContext} exactly as the HTTP
 * interceptor does; V60's forced RLS is the isolation boundary under test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_messaging_store_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class JdbcMessagingStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String UNIT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String UNIT_B_ONLY = "aaaaaaaa-0000-0000-0000-000000000002";
    static final String PATIENT_ID = "cccccccc-0000-0000-0000-000000000001";
    static final String UNLINKED_PATIENT = "cccccccc-0000-0000-0000-000000000002";
    static final String RELATIONSHIP_ID = "bbbbbbbb-0000-0000-0000-000000000001";
    static final String CONSUME_RELATIONSHIP_ID = "bbbbbbbb-0000-0000-0000-000000000002";
    static final String MISSING_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    static final String SUBJECT_A = "patient-1111";
    static final String SUBJECT_B = "patient-2222";
    static final String INACTIVE_A = "inactive-1111";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_messaging_store_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    MessagingStore messagingStore;

    @Autowired
    JdbcMessagingStore store;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_messaging_store_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_messaging_store_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, SUBJECT_A, "ref-A", "MRN-MSG-A", false);
            seedTenant(statement, ORG_B, SUBJECT_B, "ref-B", "MRN-MSG-B", true);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_messaging_store_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, String subject,
                                   String reference, String mrn, boolean extraUnit) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + INACTIVE_A + "', '" + INACTIVE_A + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'revoked' FROM public.user_identity WHERE subject = '" + INACTIVE_A + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'patient', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");

        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT_ID + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Messaging Patient', 'synthetic messaging patient', DATE '1990-05-20')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + UNLINKED_PATIENT + "', 1, '" + mrn + "-UNLINK', 'CLINIC', "
                + "'Synthetic Unlinked Patient', 'synthetic unlinked patient', DATE '1991-06-21')");
        statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        String account = "(SELECT id FROM public.patient_account WHERE organization_id = '" + org
                + "' AND subject = '" + subject + "')";
        statement.execute("INSERT INTO public.account_chart_link (organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', " + account + ", '" + PATIENT_ID + "', 'linked', 'synthetic')");
        statement.execute("INSERT INTO public.account_chart_link (organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', " + account + ", '" + UNLINKED_PATIENT + "', 'linked', 'synthetic')");
        statement.execute("INSERT INTO public.account_chart_link (organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', " + account + ", '" + UNLINKED_PATIENT + "', 'unlinked', 'synthetic')");

        statement.execute("INSERT INTO public.care_unit "
                + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                + "VALUES ('" + org + "', '" + UNIT_ID + "', '" + PATIENT_ID + "', 'Synthetic Clinic Unit', "
                + "ARRAY['messages', 'follow-up'], true, 'clinic')");
        if (extraUnit) {
            statement.execute("INSERT INTO public.care_unit "
                    + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                    + "VALUES ('" + org + "', '" + UNIT_B_ONLY + "', '" + PATIENT_ID + "', "
                    + "'Beta Only Unit', ARRAY['messages'], true, 'lab')");
        }
        insertRelationship(statement, org, RELATIONSHIP_ID, reference);
        insertRelationship(statement, org, CONSUME_RELATIONSHIP_ID, "consume-" + reference);
    }

    private static void insertRelationship(Statement statement, String org, String id, String reference)
            throws Exception {
        statement.execute("INSERT INTO public.eligible_relationship "
                + "(organization_id, id, patient_id, unit_id, context, reference, active) "
                + "VALUES ('" + org + "', '" + id + "', '" + PATIENT_ID + "', '" + UNIT_ID + "', "
                + "'order', '" + reference + "', true)");
    }

    /**
     * Bind the active tenant on the calling thread, as the HTTP interceptor does.
     */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "MESSAGING", "messaging-store-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static String id(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static PatientUnitConnection connection(String org, String connectionId, String reference) {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        return new PatientUnitConnection(connectionId, org, PATIENT_ID, UNIT_ID,
                ConnectionContext.ORDER, reference, RELATIONSHIP_ID, ConnectionState.ACTIVE,
                "patient-1111", now, now, null);
    }

    @Test
    void productionBeanIsTheJdbcStore() {
        assertNotNull(store);
        assertTrue(messagingStore instanceof JdbcMessagingStore,
                "the production MessagingStore bean must be the PostgreSQL adapter");
    }

    @Test
    void membershipAndRolesComeFromAuthoritativeFolds() {
        assertTrue(as(ORG_A, SUBJECT_A, () -> store.hasActiveMembership(ORG_A, SUBJECT_A)));
        assertFalse(as(ORG_A, SUBJECT_A, () -> store.hasActiveMembership(ORG_A, INACTIVE_A)));
        assertEquals(List.of("patient"), as(ORG_A, SUBJECT_A, () -> store.activeRoles(ORG_A, SUBJECT_A)));
        assertTrue(as(ORG_A, SUBJECT_A, () -> store.activeRoles(ORG_A, INACTIVE_A)).isEmpty());
    }

    @Test
    void verifiedPatientChartsUseTheLiveLinkFold() {
        Set<String> charts = as(ORG_A, SUBJECT_A,
                () -> store.verifiedPatientCharts(ORG_A, SUBJECT_A));
        assertEquals(Set.of(PATIENT_ID), charts);
        assertTrue(as(ORG_A, SUBJECT_A,
                () -> store.verifiedPatientCharts(ORG_A, INACTIVE_A)).isEmpty());
        assertEquals(Set.of(PATIENT_ID), as(ORG_B, SUBJECT_B,
                () -> store.verifiedPatientCharts(ORG_B, SUBJECT_B)));
    }

    @Test
    void patientExistsIsTenantScoped() {
        assertTrue(as(ORG_A, SUBJECT_A, () -> store.patientExists(ORG_A, PATIENT_ID)));
        assertFalse(as(ORG_A, SUBJECT_A, () -> store.patientExists(ORG_A, MISSING_ID)));
        assertTrue(as(ORG_B, SUBJECT_B, () -> store.patientExists(ORG_B, PATIENT_ID)));
        assertFalse(as(ORG_B, SUBJECT_B, () -> store.patientExists(ORG_B, MISSING_ID)));
    }

    @Test
    void careUnitsRoundTripWithIdenticalLocalIdsButDistinctTenants() {
        CareUnit a = as(ORG_A, SUBJECT_A, () -> store.findUnit(ORG_A, UNIT_ID).orElseThrow());
        CareUnit b = as(ORG_B, SUBJECT_B, () -> store.findUnit(ORG_B, UNIT_ID).orElseThrow());
        assertEquals(ORG_A, a.organizationId());
        assertEquals(ORG_B, b.organizationId());
        assertEquals(List.of("messages", "follow-up"), a.topics());
        assertEquals("clinic", a.unitKey());
        assertTrue(a.active());
        assertEquals("Synthetic Clinic Unit", a.name());
        assertTrue(as(ORG_A, SUBJECT_A, () -> store.findUnit(ORG_A, UNIT_B_ONLY)).isEmpty(),
                "tenant A cannot see a unit that exists only in tenant B");
        assertTrue(as(ORG_A, SUBJECT_A, () -> store.findUnit(ORG_A, MISSING_ID)).isEmpty());
    }

    @Test
    void relationshipsAreTenantScopedAndConsumptionIsIdempotent() {
        List<EligibleRelationship> found = as(ORG_A, SUBJECT_A,
                () -> store.relationshipsFor(ORG_A, PATIENT_ID));
        assertEquals(2, found.size());
        EligibleRelationship a = as(ORG_A, SUBJECT_A,
                () -> store.relationship(ORG_A, RELATIONSHIP_ID).orElseThrow());
        EligibleRelationship b = as(ORG_B, SUBJECT_B,
                () -> store.relationship(ORG_B, RELATIONSHIP_ID).orElseThrow());
        assertEquals("ref-A", a.reference());
        assertEquals("ref-B", b.reference());
        assertEquals(ConnectionContext.ORDER, a.context());
        assertFalse(a.consumed());

        as(ORG_A, SUBJECT_A, () -> {
            store.consumeRelationship(ORG_A, CONSUME_RELATIONSHIP_ID);
            return null;
        });
        assertTrue(as(ORG_A, SUBJECT_A,
                () -> store.relationship(ORG_A, CONSUME_RELATIONSHIP_ID).orElseThrow()).consumed());
        // Re-consuming and consuming an unknown id are both no-ops.
        as(ORG_A, SUBJECT_A, () -> {
            store.consumeRelationship(ORG_A, CONSUME_RELATIONSHIP_ID);
            store.consumeRelationship(ORG_A, MISSING_ID);
            return null;
        });
        assertFalse(as(ORG_B, SUBJECT_B,
                () -> store.relationship(ORG_B, CONSUME_RELATIONSHIP_ID).orElseThrow()).consumed(),
                "consuming in tenant A did not consume tenant B's identical relationship");
    }

    @Test
    void connectionsRoundTripAndConflictReturnsTheOriginal() {
        String connectionId = id("connection-roundtrip");
        String duplicateId = id("connection-duplicate");
        String reference = "connection-ref-" + ORG_A;
        PatientUnitConnection saved = as(ORG_A, SUBJECT_A,
                () -> store.insertConnection(ORG_A, connection(ORG_A, connectionId, reference)));
        assertEquals(connectionId, saved.id());
        assertEquals(ConnectionState.ACTIVE, saved.state());

        PatientUnitConnection reread = as(ORG_A, SUBJECT_A,
                () -> store.findConnection(ORG_A, connectionId).orElseThrow());
        assertEquals(saved, reread);
        assertEquals(saved, as(ORG_A, SUBJECT_A,
                () -> store.findConnectionByReference(ORG_A, PATIENT_ID, UNIT_ID, reference).orElseThrow()));
        assertTrue(as(ORG_A, SUBJECT_A,
                () -> store.connectionsFor(ORG_A, PATIENT_ID)).stream()
                .anyMatch(connection -> connectionId.equals(connection.id())));

        // Same business key, different opaque id: the winner is returned.
        PatientUnitConnection duplicate = connection(ORG_A, duplicateId, reference);
        PatientUnitConnection winner = as(ORG_A, SUBJECT_A,
                () -> store.insertConnection(ORG_A, duplicate));
        assertEquals(connectionId, winner.id());
        assertTrue(as(ORG_A, SUBJECT_A, () -> store.findConnection(ORG_A, duplicateId)).isEmpty());
    }

    @Test
    void revocationStopsAccessAndIsIdempotent() {
        String connectionId = id("connection-revoke");
        String reference = "revoke-ref-" + ORG_A;
        as(ORG_A, SUBJECT_A, () -> {
            store.insertConnection(ORG_A, connection(ORG_A, connectionId, reference));
            return null;
        });
        Instant revokedAt = Instant.parse("2026-11-21T11:00:00Z").truncatedTo(ChronoUnit.SECONDS);
        as(ORG_A, SUBJECT_A, () -> {
            store.revokeConnection(ORG_A, connectionId, revokedAt);
            return null;
        });
        PatientUnitConnection revoked = as(ORG_A, SUBJECT_A,
                () -> store.findConnection(ORG_A, connectionId).orElseThrow());
        assertEquals(ConnectionState.REVOKED, revoked.state());
        assertEquals(revokedAt, revoked.revokedAt());
        // Re-revoking and revoking an unknown id are both no-ops.
        as(ORG_A, SUBJECT_A, () -> {
            store.revokeConnection(ORG_A, connectionId, revokedAt);
            store.revokeConnection(ORG_A, MISSING_ID, revokedAt);
            return null;
        });
        assertEquals(revoked, as(ORG_A, SUBJECT_A,
                () -> store.findConnection(ORG_A, connectionId).orElseThrow()));
    }

    @Test
    void auditFactsAreOrderedAndTenantScoped() {
        String first = id("audit-first");
        String second = id("audit-second");
        Instant firstAt = Instant.parse("2026-11-21T12:00:00Z");
        Instant secondAt = firstAt.plusSeconds(1);
        as(ORG_A, SUBJECT_A, () -> {
            store.appendAudit(new MessagingAudit(ORG_A, SUBJECT_A, "CONNECTION_CREATE", first, "SUCCESS", firstAt));
            store.appendAudit(new MessagingAudit(ORG_A, SUBJECT_A, "CONNECTION_USE", second, "DENIED", secondAt));
            return null;
        });
        as(ORG_B, SUBJECT_B, () -> {
            store.appendAudit(new MessagingAudit(ORG_B, SUBJECT_B, "CONNECTION_CREATE", first, "SUCCESS", firstAt));
            return null;
        });

        List<MessagingAudit> a = as(ORG_A, SUBJECT_A, () -> store.auditEvents(ORG_A));
        List<MessagingAudit> b = as(ORG_B, SUBJECT_B, () -> store.auditEvents(ORG_B));
        assertTrue(a.stream().allMatch(fact -> ORG_A.equals(fact.organizationId())));
        assertTrue(b.stream().allMatch(fact -> ORG_B.equals(fact.organizationId())));
        int firstIndex = indexOf(a, first);
        int secondIndex = indexOf(a, second);
        assertTrue(firstIndex >= 0 && secondIndex > firstIndex,
                "audit facts must be returned in insertion order");
        MessagingAudit fact = a.get(secondIndex);
        assertEquals("DENIED", fact.outcome());
        assertEquals(secondAt, fact.occurredAt());
        assertTrue(b.stream().anyMatch(item -> first.equals(item.resourceId())),
                "tenant B sees only its own identical-local-id fact");
    }

    @Test
    void auditFactsAreImmutableAndCrossTenantWritesFailClosed() throws Exception {
        String resourceId = id("audit-immutable");
        Instant at = Instant.parse("2026-11-21T13:00:00Z");
        as(ORG_A, SUBJECT_A, () -> {
            store.appendAudit(new MessagingAudit(ORG_A, SUBJECT_A, "CONNECTION_CREATE", resourceId,
                    "SUCCESS", at));
            return null;
        });

        // WITH CHECK rejects a row whose organization_id is not the bound tenant.
        assertThrows(Exception.class, () -> as(ORG_A, SUBJECT_A, () -> {
            store.appendAudit(new MessagingAudit(ORG_B, SUBJECT_B, "CONNECTION_CREATE",
                    id("audit-cross-tenant"), "SUCCESS", at));
            return null;
        }));

        // The append-only trigger rejects an UPDATE for every role, including the
        // migration owner; a GRANT alone would not cover the owner.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.messaging_audit SET outcome = 'DENIED' WHERE resource_id = '"
                            + resourceId + "'"));
        }
    }

    private static int indexOf(List<MessagingAudit> facts, String resourceId) {
        for (int index = 0; index < facts.size(); index++) {
            if (resourceId.equals(facts.get(index).resourceId())) {
                return index;
            }
        }
        return -1;
    }
}
