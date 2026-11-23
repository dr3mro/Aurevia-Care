package com.aurevia.smartclinic.tenant.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ARC-01 clause 5 (schema evidence): the key models named in the specification
 * are tenant-owned records with an organization identifier and an opaque
 * identifier, and their durable rows are isolated by forced row-level security.
 *
 * <p>The positive tests read the migrated schema with read-only metadata queries.
 * Each concrete relation that implements a named model must carry an
 * {@code organization_id} and an opaque uuid {@code id}, and must enforce forced
 * RLS. The negative tests prove the rule refuses a wrong candidate, a peer
 * tenant reading the same local opaque id, and a caller with no tenant context.
 * EncounterRevision has no relation in this baseline; that gap is recorded by an
 * enabled assertion so it cannot be silently claimed as proven.</p>
 *
 * <p>Synthetic fixtures only. No production code, migration or contract is added
 * or changed by this suite: the Flyway migrations under {@code db/migrations}
 * are the entry point under test, read through the same real-PostgreSQL harness
 * as {@code com.aurevia.smartclinic.tenancy.clauses.IsolationAcrossSurfacesTest}.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_arc01_keymodels_it")
@ActiveProfiles("synthetic-auth")
class KeyModelsIncludeAppointmentQueueentryEncounterrevisionTemplateversionTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String SHARED_LOCAL_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_arc01_keymodels_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    /**
     * Every relation that implements a named key model with an organization id
     * and an opaque uuid id. The comment on each entry names the clause model it
     * proves; three models (Specimen, Signature and MessageAttachment) are
     * carried as columns of these relations rather than as dedicated tables.
     */
    static final List<String> OPAQUE_ID_RELATIONS = List.of(
            "appointment",                      // Appointment
            "worklist_entry",                   // QueueEntry
            "exchange_order",                   // Order
            "lab_accession",                    // Specimen (specimen_type, site_code)
            "trend_observation",                // Observation
            "xch04_report_revision",            // ReportRevision
            "clinical_note_revision",           // Signature (signed_at, signer_subject, attestation)
            "patient_release",                  // PatientRelease
            "xch04_review",                     // ReviewTask
            "exchange_authorization",           // ExchangeGrant
            "delivery_outbox",                  // DeliveryEvent
            "delivery_receipt",                 // DeliveryEvent receipt
            "attachment",                       // Attachment and MessageAttachment (message_id)
            "therapy_episode",                  // TherapyEpisode
            "prescription_draft",               // Prescription
            "finance_invoice",                  // Invoice
            "finance_ledger_entry",             // PaymentEntry
            "care_unit",                        // CareUnit
            "patient_unit_connection",          // PatientUnitConnection
            "conversation",                     // Conversation
            "conversation_message",             // Message
            "instruction_fact_instruction",     // InstructionRevision
            "instruction_fact_acknowledgment",  // InstructionAcknowledgment
            "audit_event"                       // AuditEvent
    );

    /** Create a fresh database from the committed migrations and seed two synthetic tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_arc01_keymodels_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_arc01_keymodels_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            // The organization table is itself RLS-protected, so each tenant
            // must be inserted under its own transaction-local context.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES "
                    + "('" + ORG_A + "', 'Key models A')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES "
                    + "('" + ORG_B + "', 'Key models B')");
            // Two tenants reuse one opaque id on a key-model relation so the
            // peer-tenant refusal cannot pass merely because the ids differ.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.care_unit "
                    + "(organization_id, id, name, topics, active, created_at) VALUES "
                    + "('" + ORG_A + "', '" + SHARED_LOCAL_ID
                    + "', 'Alpha unit', '{}'::text[], true, now())");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.care_unit "
                    + "(organization_id, id, name, topics, active, created_at) VALUES "
                    + "('" + ORG_B + "', '" + SHARED_LOCAL_ID
                    + "', 'Beta unit', '{}'::text[], true, now())");
        }
    }

    /** Drop the scratch database so the suite leaves no durable state behind. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_arc01_keymodels_it WITH (FORCE)");
        }
    }

    /**
     * Return the PostgreSQL data type of a column, or {@code null} when the
     * relation or column does not exist. Uses read-only metadata only.
     */
    private static String columnType(String relation, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT data_type FROM information_schema.columns "
                     + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            query.setString(1, relation);
            query.setString(2, column);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    /** Return whether a tenant relation exists at all in the migrated schema. */
    private static boolean relationExists(String relation) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                     + "WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname = ?")) {
            query.setString(1, relation);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    /** Return whether both row-level security and FORCE ROW LEVEL SECURITY are on. */
    private static boolean hasForcedRls(String relation) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT c.relrowsecurity AND c.relforcerowsecurity FROM pg_class c "
                     + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                     + "WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname = ?")) {
            query.setString(1, relation);
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /**
     * Assert the tenant-key invariant for one relation: a uuid organization id,
     * an opaque uuid id and forced RLS. Each assertion names the relation so a
     * failure identifies the candidate that broke the proof.
     */
    private static void assertTenantOwnedOpaqueIdRelation(String relation) throws Exception {
        assertEquals("uuid", columnType(relation, "organization_id"),
                relation + " must carry an organization_id");
        assertEquals("uuid", columnType(relation, "id"),
                relation + " must carry an opaque uuid id");
        assertTrue(hasForcedRls(relation),
                relation + " must enforce forced row-level security");
    }

    /**
     * Read the care_unit rows visible to the runtime role under a given tenant.
     * A {@code null} organization means no context is set, which must fail closed.
     */
    private static List<String> visibleCareUnitOrgs(String organization) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            if (organization == null) {
                statement.execute("RESET app.current_org");
            } else {
                statement.execute("SET app.current_org = '" + organization + "'");
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT organization_id FROM public.care_unit WHERE id = '" + SHARED_LOCAL_ID + "'")) {
                List<String> visible = new ArrayList<>();
                while (result.next()) {
                    visible.add(result.getString(1));
                }
                return visible;
            }
        }
    }

    /** Every concrete key-model relation carries the two tenant keys and forced RLS. */
    @Test
    void everyKeyModelRelationCarriesOrganizationIdAndOpaqueId() throws Exception {
        for (String relation : OPAQUE_ID_RELATIONS) {
            assertTenantOwnedOpaqueIdRelation(relation);
        }
    }

    /**
     * The key models with a natural key or a column-only representation are
     * still tenant-owned, and the gaps in their identifier shape are explicit.
     */
    @Test
    void naturalKeyAndColumnCarriedKeyModelsStayTenantOwned() throws Exception {
        assertEquals("uuid", columnType("template_version", "organization_id"));
        assertEquals("text", columnType("template_version", "kind"));
        assertEquals("integer", columnType("template_version", "version"));
        assertNull(columnType("template_version", "id"),
                "GAP: template_version is keyed by (organization_id, kind, version), not an opaque id");
        assertTrue(hasForcedRls("template_version"));

        assertEquals("text", columnType("lab_accession", "specimen_type"),
                "Specimen is carried on the tenant-owned lab accession");
        assertEquals("text", columnType("lab_accession", "site_code"));
        assertEquals("timestamp with time zone", columnType("clinical_note_revision", "signed_at"),
                "Signature is carried on the tenant-owned note revision");
        assertEquals("text", columnType("clinical_note_revision", "signer_subject"));
        assertEquals("text", columnType("clinical_note_revision", "attestation"));
        assertEquals("text", columnType("attachment", "message_id"),
                "MessageAttachment is carried on the tenant-owned attachment");
        assertEquals("integer", columnType("instruction_fact_instruction", "revision"),
                "InstructionRevision is the revision column of the tenant-owned instruction fact");
    }

    /**
     * Forced RLS conceals a peer tenant's row even when both tenants use the
     * same opaque id, and a caller with no tenant context sees no row at all.
     */
    @Test
    void peerTenantCannotReadAKeyModelRowWithTheSameLocalId() throws Exception {
        assertEquals(List.of(ORG_A), visibleCareUnitOrgs(ORG_A));
        assertEquals(List.of(ORG_B), visibleCareUnitOrgs(ORG_B));
        assertTrue(visibleCareUnitOrgs(null).isEmpty(),
                "a missing tenant context must fail closed instead of returning either row");
    }

    /** A real table that is a global catalogue must be rejected by the proof. */
    @Test
    void keyModelRelationMissingOrganizationIdFailsTheProof() {
        AssertionError rejected = assertThrows(AssertionError.class,
                () -> assertTenantOwnedOpaqueIdRelation("clinical_template_baseline"));
        assertTrue(rejected.getMessage().contains("organization_id"),
                "the proof must reject a candidate without an organization_id");
    }

    /**
     * Characterise the absent EncounterRevision relation. The assertion is
     * enabled and green today; when that relation lands the assertion flips,
     * forcing the next task to replace it with the tenant-key proof instead of
     * leaving a silently skipped test behind.
     */
    @Test
    void encounterRevisionRelationIsNotPresentSoTheProofRemainsPartial() throws Exception {
        assertFalse(relationExists("encounter_revision"),
                "GAP: no encounter_revision relation exists; V15 keeps encounter_id as a plain uuid "
                + "and defers the composite foreign key until the encounter table lands");
    }
}
