package com.aurevia.pharmacyflow.substitution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.ProposalView;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.State;
import com.aurevia.tenant.TenantContext;

import org.flywaydb.core.Flyway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RX-01/RX-02 substitution durability on a real PostgreSQL instance.
 *
 * <p>The narrative drives the public HTTP routes with synthetic staff and then
 * reads the authoritative prescription, the substitution trail and the audit
 * ledger directly. A decision must be one atomic transaction: a retry or a
 * stale or unauthorized approval can never leave a partial write. Tagged
 * {@code integration} so the swarm fast gate skips it; the integrator runs it
 * with a real database.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_substitution_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SubstitutionPersistenceIntegrationTest {

    private static final String ORG = "71111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "72222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_substitution_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PHARMACIST = "pharmacy-substitution-it";
    private static final String PRESCRIBER = "prescriber-substitution-it";
    private static final String UNENTITLED = "unentitled-substitution-it";
    private static final String DRUG_ORIGINAL = "drug-original-it";
    private static final String DRUG_ALTERNATIVE = "drug-alternative-it";
    private static final String[] PRESCRIPTIONS = {
            "73333333-3333-3333-3333-333333333331",
            "73333333-3333-3333-3333-333333333332",
            "73333333-3333-3333-3333-333333333333",
            "73333333-3333-3333-3333-333333333334",
            "73333333-3333-3333-3333-333333333335",
            "73333333-3333-3333-3333-333333333336"
    };
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_PRESCRIPTION =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private SubstitutionCatalog substitutionCatalog;
    @Autowired
    private SubstitutionStore substitutionStore;
    @Autowired
    private SubstitutionApplication substitutionApplication;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private AuditAppender audit;

    private RestClient rest;
    private String prescription;

    /** Initialize the REST client and give each narrative its own untouched draft. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        prescription = PRESCRIPTIONS[NEXT_PRESCRIPTION.getAndIncrement() % PRESCRIPTIONS.length];
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate and migrate the database, then seed two isolated synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_substitution_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_substitution_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG);
            seed(statement, OTHER_ORG);
        }
    }

    /** Drop the synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_substitution_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-substitution-it'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic substitution tenant')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES "
                + "('" + org + "', 'bootstrap-substitution-it', 'Bootstrap'), "
                + "('" + org + "', '" + PHARMACIST + "', 'Pharmacy'), "
                + "('" + org + "', '" + PRESCRIBER + "', 'Prescriber'), "
                + "('" + org + "', '" + UNENTITLED + "', 'Unentitled')");
        for (String subject : new String[] {PHARMACIST, PRESCRIBER, UNENTITLED}) {
            String role = PHARMACIST.equals(subject) ? "pharmacy_staff" : "clinician";
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                    + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
            statement.execute("INSERT INTO public.role_assignment "
                    + "(organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                    + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
        }
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-SUB-IT', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
        statement.execute("INSERT INTO public.patient_allergy_status "
                + "(organization_id, patient_id, status, source) "
                + "SELECT '" + org + "', id, 'verified_none', 'synthetic.fixture' "
                + "FROM public.patient_record WHERE mrn = 'MRN-SUB-IT'");
        for (String[] drug : new String[][] {{DRUG_ORIGINAL, "Panadol"}, {DRUG_ALTERNATIVE, "Calpol"}}) {
            statement.execute("INSERT INTO public.drug_catalog_entry "
                    + "(organization_id, entry_key, version, generic_name, trade_name, formulation, "
                    + " strength_value, strength_unit, route, substance_ids, substance_groups, "
                    + " group_versions, approved, effective_from, effective_to, approval_provenance) "
                    + "VALUES ('" + org + "', '" + drug[0] + "', 1, 'Paracetamol', '" + drug[1] + "', "
                    + "'tablet', '500', 'mg', 'oral', ARRAY['PARACETAMOL-SUB']::text[], "
                    + "ARRAY['ANALGESIC']::text[], ARRAY[1]::integer[], true, "
                    + "now() - interval '2 days', null, 'synthetic-catalog-v1')");
        }
        statement.execute("INSERT INTO public.medication_catalog_version "
                + "(organization_id, version, market, provenance) "
                + "VALUES ('" + org + "', 1, 'synthetic-market', 'synthetic-catalog-v1')");
        // V43 derives the prescriber from the transaction actor; seed as the
        // named prescriber so the proposal is owned by the approving clinician.
        statement.execute("SET app.actor = '" + PRESCRIBER + "'");
        for (String id : PRESCRIPTIONS) {
            statement.execute("INSERT INTO public.prescription_draft "
                    + "(organization_id, id, version, patient_id, state, selected_entry_key, "
                    + " selected_entry_version, drug_generic_name, dose_value, dose_unit, route, "
                    + " frequency, quantity, quantity_unit, instructions, prescriber_subject, created_by) "
                    + "SELECT '" + org + "', '" + id + "', 1, "
                    + "(SELECT id FROM public.patient_record WHERE mrn = 'MRN-SUB-IT'), 'draft', '"
                    + DRUG_ORIGINAL + "', "
                    + "1, 'Paracetamol', '500', 'mg', 'oral', 'twice daily', '60', 'tablet', "
                    + "'take with water', '" + PRESCRIBER + "', u.id FROM public.user_identity u "
                    + "WHERE u.subject = 'bootstrap-substitution-it'");
        }
        policy(statement, org, "rx02.market", "synthetic-market");
        policy(statement, org, "rx02.entitlement." + PRESCRIBER, "enabled");
        policy(statement, org, "rx02.entitlement." + UNENTITLED, "disabled");
        policy(statement, org, "rx02.approved_channels", "E_PRESCRIBE,PRINTABLE_LOCAL");
    }

    private static void policy(Statement statement, String org, String key, String value) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-substitution-it'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    private java.util.function.Consumer<HttpHeaders> headers(String subject, String org) {
        return headers -> {
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Synthetic-Subject", subject);
            headers.set("X-Active-Organization", org);
        };
    }

    private JsonNode propose(String key) {
        String body = "{\"currentEntryId\":\"" + DRUG_ORIGINAL + "\",\"proposedEntryId\":\""
                + DRUG_ALTERNATIVE + "\",\"reason\":\"stock\",\"note\":\"synthetic shortage\"}";
        return rest.post().uri("/api/v1/pharmacy/substitutions/" + prescription + "/proposals")
                .headers(headers(PHARMACIST, ORG)).header("Idempotency-Key", key).body(body).retrieve()
                .body(JsonNode.class);
    }

    private String proposalId(JsonNode proposal) {
        return proposal.path("proposal").path("id").asText();
    }

    private void decide(String proposalId, String action, String subject, String ifMatch, String key,
                        String attestation) {
        String body = "{\"attestation\":" + (attestation == null ? "null" : "\"" + attestation + "\"")
                + ",\"note\":null}";
        rest.post().uri("/api/v1/pharmacy/substitutions/" + proposalId + "/" + action)
                .headers(headers(subject, ORG)).header("If-Match", ifMatch)
                .header("Idempotency-Key", key).body(body).retrieve().toBodilessEntity();
    }

    private JsonNode view(String proposalId, String subject, String org) {
        return rest.get().uri("/api/v1/pharmacy/substitutions/" + proposalId)
                .headers(headers(subject, org)).retrieve().body(JsonNode.class);
    }

    private int statusOf(Runnable request) {
        try {
            request.run();
            return 200;
        } catch (org.springframework.web.client.HttpStatusCodeException exception) {
            return exception.getStatusCode().value();
        }
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ORG);
            try (Statement guc = connection.createStatement()) {
                guc.execute("SET app.current_org = '" + ORG + "'");
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Test
    void approvalPersistsDecisionAppliesPrescriptionAndAudits() throws Exception {
        String id = proposalId(propose("it-approve-propose"));
        assertNotNull(id);
        assertEquals(1, count("SELECT count(*) FROM public.pharmacy_substitution_proposal "
                + "WHERE organization_id = CAST(? AS uuid) AND id = CAST('" + id + "' AS uuid)"),
                "proposal row after propose");

        decide(id, "approve", PRESCRIBER, "1", "it-approve", "synthetic attestation");

        JsonNode pharmacyView = view(id, PHARMACIST, ORG);
        assertEquals("approved", pharmacyView.path("proposal").path("state").asText());
        assertEquals(DRUG_ALTERNATIVE,
                pharmacyView.path("appliedRevision").path("replacement").path("entryId").asText());
        JsonNode prescriberView = view(id, PRESCRIBER, ORG);
        assertEquals("approved", prescriberView.path("proposal").path("state").asText());
        assertEquals("synthetic attestation",
                prescriberView.path("appliedRevision").path("attestation").asText());

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            try (ResultSet result = statement.executeQuery("SELECT selected_entry_key, version "
                    + "FROM public.prescription_draft WHERE id = '" + prescription + "'")) {
                assertTrue(result.next());
                assertEquals(DRUG_ALTERNATIVE, result.getString(1));
                assertEquals(2, result.getInt(2));
            }
        }
        assertEquals(1, count("SELECT count(*) FROM public.pharmacy_substitution_proposal "
                + "WHERE organization_id = CAST(? AS uuid) AND state = 'approved' "
                + "AND id = CAST('" + id + "' AS uuid)"));
        assertEquals(1, count("SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST(? AS uuid) AND proposal_id = CAST('" + id + "' AS uuid)"));
        assertTrue(count("SELECT count(*) FROM public.audit_event WHERE organization_id = CAST(? AS uuid) "
                + "AND action = 'SUBSTITUTION_DECIDED' AND outcome = 'SUCCESS'") >= 1);
    }

    @Test
    void unauthorizedPrescriberLeavesEveryRecordUnchanged() throws Exception {
        String id = proposalId(propose("it-unentitled-propose"));
        assertNotNull(id);
        int drafts = count("SELECT count(*) FROM public.prescription_draft WHERE organization_id = CAST(? AS uuid)");

        assertTrue(statusOf(() -> decide(id, "approve", UNENTITLED, "1", "it-unentitled",
                "synthetic attestation")) >= 400);

        assertEquals("pending", view(id, PHARMACIST, ORG).path("proposal").path("state").asText());
        assertEquals(0, count("SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST(? AS uuid) AND proposal_id = CAST('" + id + "' AS uuid)"));
        assertEquals(drafts, count("SELECT count(*) FROM public.prescription_draft "
                + "WHERE organization_id = CAST(? AS uuid)"));
        assertEquals(0, count("SELECT count(*) FROM public.audit_event "
                + "WHERE organization_id = CAST(? AS uuid) AND resource_id = '" + id + "'"));
    }

    @Test
    void sameKeyRetryProducesOneDecision() throws Exception {
        String id = proposalId(propose("it-retry-propose"));
        assertNotNull(id);
        decide(id, "approve", PRESCRIBER, "1", "it-retry", "synthetic attestation");
        decide(id, "approve", PRESCRIBER, "1", "it-retry", "synthetic attestation");

        assertEquals(1, count("SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST(? AS uuid) AND proposal_id = CAST('" + id + "' AS uuid)"));
    }

    @Test
    void staleApprovalCannotWin() throws Exception {
        String id = proposalId(propose("it-stale-propose"));
        assertNotNull(id);
        decide(id, "decline", PRESCRIBER, "1", "it-stale-decline", null);
        assertTrue(statusOf(() -> decide(id, "approve", PRESCRIBER, "1", "it-stale-approve",
                "synthetic attestation")) >= 400);

        assertEquals("declined", view(id, PHARMACIST, ORG).path("proposal").path("state").asText());
        assertEquals(0, count("SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST(? AS uuid) AND proposal_id = CAST('" + id + "' AS uuid)"));
    }

    @Test
    void newServiceInstanceReadsThePersistedDecision() {
        String id = proposalId(propose("it-restart-propose"));
        assertNotNull(id);
        decide(id, "approve", PRESCRIBER, "1", "it-restart", "synthetic attestation");

        TenantContext.set(new TenantContext(ORG, PRESCRIBER, TenantContext.PURPOSE, "it-restart-instance"));
        try {
            SubstitutionService fresh = new SubstitutionService(Clock.systemUTC(), substitutionCatalog,
                    substitutionStore, substitutionApplication, transactions, audit);
            ProposalView persisted = fresh.view(ORG, id, PRESCRIBER, Set.of(SpecialtyRoles.CLINICIAN));
            assertEquals(State.APPROVED, persisted.proposal().state());
            assertNotNull(persisted.appliedRevision());
            assertEquals("synthetic attestation", persisted.appliedRevision().attestation());
            assertEquals(DRUG_ALTERNATIVE, persisted.appliedRevision().replacement().entryId());
            assertEquals(1, persisted.alternatives().size());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void foreignTenantCannotReadOrDecide() throws Exception {
        String id = proposalId(propose("it-tenant-propose"));
        assertNotNull(id);
        assertEquals(404, statusOf(() -> rest.get().uri("/api/v1/pharmacy/substitutions/" + id)
                .headers(headers(PHARMACIST, OTHER_ORG)).retrieve().toBodilessEntity()));
    }
}
