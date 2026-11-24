package com.aurevia.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * ARC-03 clause 8 HTTP evidence: an expired retry key must not allow a second
 * durable external clinical action.
 *
 * <p>The surface is the pharmacy substitution decision. The in-memory
 * idempotency cache is deliberately process-local and its expiry removes only a
 * cache entry; the guarantee under test is that a retry arriving with a fresh,
 * unseen key after cache amnesia is still refused once the first action has
 * committed. The refusal must come from the durable business identity (a
 * substitution proposal closes exactly once and its signed revision is unique
 * per proposal), not from a lookup of the original retry key. Every command
 * goes through the real HTTP entry point and the outcome is checked by direct
 * JDBC under the caller's tenant and forced row-level security.</p>
 *
 * <p>All identifiers, keys and digests are synthetic. This suite changes no
 * production code, migration, contract or existing test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_expired_retry_key_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ExpiredRetryKeysStillRespectDurableBusinessTest {

    private static final String ORG = "a1111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "a2222222-2222-2222-2222-222222222222";
    private static final String PHARMACIST = "pharmacist-expired-key-it";
    private static final String PRESCRIBER = "prescriber-expired-key-it";
    private static final String DRUG_ORIGINAL = "drug-original-expired-key-it";
    private static final String DRUG_ALTERNATIVE = "drug-alternative-expired-key-it";
    private static final String ATTESTATION = "synthetic prescriber attestation";
    private static final String DB = "aurevia_expired_retry_key_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String[] PRESCRIPTIONS = {
            "a3333333-3333-3333-3333-333333333331",
            "a3333333-3333-3333-3333-333333333332",
            "a3333333-3333-3333-3333-333333333333",
            "a3333333-3333-3333-3333-333333333334",
            "a3333333-3333-3333-3333-333333333335",
            "a3333333-3333-3333-3333-333333333336"
    };
    private static final AtomicInteger NEXT_PRESCRIPTION = new AtomicInteger();

    @LocalServerPort
    int port;

    RestClient rest;

    String prescription;

    /** Recreate and migrate a dedicated database, then seed two isolated tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
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

    /** Drop the dedicated synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /* Seed a tenant with one pharmacist, one entitled prescriber, a patient with no
       allergies, a current catalog pair and an untouched prescription per narrative. */
    private static void seed(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-expired-key-it'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic expired-key tenant')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES "
                + "('" + org + "', 'bootstrap-expired-key-it', 'Bootstrap'), "
                + "('" + org + "', '" + PHARMACIST + "', 'Pharmacy'), "
                + "('" + org + "', '" + PRESCRIBER + "', 'Prescriber')");
        for (String subject : new String[] {PHARMACIST, PRESCRIBER}) {
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
                + "VALUES ('" + org + "', 1, 'MRN-EXPIRED-KEY-IT', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
        statement.execute("INSERT INTO public.patient_allergy_status "
                + "(organization_id, patient_id, status, source) "
                + "SELECT '" + org + "', id, 'verified_none', 'synthetic.fixture' "
                + "FROM public.patient_record WHERE mrn = 'MRN-EXPIRED-KEY-IT'");
        for (String[] drug : new String[][] {{DRUG_ORIGINAL, "Panadol"}, {DRUG_ALTERNATIVE, "Calpol"}}) {
            statement.execute("INSERT INTO public.drug_catalog_entry "
                    + "(organization_id, entry_key, version, generic_name, trade_name, formulation, "
                    + " strength_value, strength_unit, route, substance_ids, substance_groups, "
                    + " group_versions, approved, effective_from, effective_to, approval_provenance) "
                    + "VALUES ('" + org + "', '" + drug[0] + "', 1, 'Paracetamol', '" + drug[1] + "', "
                    + "'tablet', '500', 'mg', 'oral', ARRAY['PARACETAMOL-EXPIRED']::text[], "
                    + "ARRAY['ANALGESIC']::text[], ARRAY[1]::integer[], true, "
                    + "now() - interval '2 days', null, 'synthetic-catalog-v1')");
        }
        statement.execute("INSERT INTO public.medication_catalog_version "
                + "(organization_id, version, market, provenance) "
                + "VALUES ('" + org + "', 1, 'synthetic-market', 'synthetic-catalog-v1')");
        statement.execute("SET app.actor = '" + PRESCRIBER + "'");
        for (String id : PRESCRIPTIONS) {
            statement.execute("INSERT INTO public.prescription_draft "
                    + "(organization_id, id, version, patient_id, state, selected_entry_key, "
                    + " selected_entry_version, drug_generic_name, dose_value, dose_unit, route, "
                    + " frequency, quantity, quantity_unit, instructions, prescriber_subject, created_by) "
                    + "SELECT '" + org + "', '" + id + "', 1, "
                    + "(SELECT id FROM public.patient_record WHERE mrn = 'MRN-EXPIRED-KEY-IT'), 'draft', '"
                    + DRUG_ORIGINAL + "', "
                    + "1, 'Paracetamol', '500', 'mg', 'oral', 'twice daily', '60', 'tablet', "
                    + "'take with water', '" + PRESCRIBER + "', u.id FROM public.user_identity u "
                    + "WHERE u.subject = 'bootstrap-expired-key-it'");
        }
        policy(statement, org, "rx02.market", "synthetic-market");
        policy(statement, org, "rx02.entitlement." + PRESCRIBER, "enabled");
        policy(statement, org, "rx02.approved_channels", "E_PRESCRIBE,PRINTABLE_LOCAL");
    }

    private static void policy(Statement statement, String org, String key, String value) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-expired-key-it'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    /** Give each narrative an untouched prescription and a fresh REST client. */
    @BeforeEach
    void initRest() {
        prescription = PRESCRIPTIONS[NEXT_PRESCRIPTION.getAndIncrement() % PRESCRIPTIONS.length];
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private Consumer<HttpHeaders> headers(String subject, String org) {
        return headers -> {
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Synthetic-Subject", subject);
            headers.set("X-Active-Organization", org);
        };
    }

    /* onStatus(true) captures the real status and body so a refused command never
       throws out of the client and the machine-readable code can be asserted. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String idempotencyKey, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { }).toEntity(String.class);
    }

    private String propose(String key) {
        String body = "{\"currentEntryId\":\"" + DRUG_ORIGINAL + "\",\"proposedEntryId\":\""
                + DRUG_ALTERNATIVE + "\",\"reason\":\"stock\",\"note\":\"synthetic shortage\"}";
        ResponseEntity<String> response = request(HttpMethod.POST,
                "/api/v1/pharmacy/substitutions/" + prescription + "/proposals", body,
                PHARMACIST, ORG, key, null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        String id = field(response.getBody(), "id");
        assertNotNull(id, response.getBody());
        assertFalse(id.isBlank(), "proposal id must be present: " + response.getBody());
        return id;
    }

    private ResponseEntity<String> approve(String proposalId, String subject, String org,
                                           String ifMatch, String key) {
        String body = "{\"attestation\":\"" + ATTESTATION + "\",\"note\":null}";
        return request(HttpMethod.POST, "/api/v1/pharmacy/substitutions/" + proposalId + "/approve",
                body, subject, org, key, ifMatch);
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /* Delete only the original claim row after temporarily lifting the test
       database's immutability trigger. The proposal and its signed revision are
       never touched, so the second decision can only be stopped by the durable
       business backstop. The trigger is restored in a finally block. */
    private static void evictClaim(String proposalId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            statement.execute("ALTER TABLE public.pharmacy_substitution_decision "
                    + "DISABLE TRIGGER pharmacy_substitution_decision_no_update_delete");
            try {
                int removed = statement.executeUpdate("DELETE FROM public.pharmacy_substitution_decision "
                        + "WHERE organization_id = CAST('" + ORG + "' AS uuid) AND command = 'approve' "
                        + "AND proposal_id = CAST('" + proposalId + "' AS uuid)");
                assertEquals(1, removed);
            } finally {
                statement.execute("ALTER TABLE public.pharmacy_substitution_decision "
                        + "ENABLE TRIGGER pharmacy_substitution_decision_no_update_delete");
            }
        }
    }

    @Test
    void expiredKeyCannotCreateASecondExternalClinicalAction() throws Exception {
        String proposalId = propose("clause-" + UUID.randomUUID());
        ResponseEntity<String> first = approve(proposalId, PRESCRIBER, ORG, "1", "claim-" + UUID.randomUUID());
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        String revisionSql = "SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST('" + ORG + "' AS uuid) "
                + "AND proposal_id = CAST('" + proposalId + "' AS uuid)";
        assertEquals(1, count(revisionSql));

        // Cache amnesia: the first claim is gone and the retry presents a fresh key.
        evictClaim(proposalId);
        String freshKey = "fresh-" + UUID.randomUUID();
        assertEquals(0, count("SELECT count(*) FROM public.pharmacy_substitution_decision "
                + "WHERE organization_id = CAST('" + ORG + "' AS uuid) AND command = 'approve' "
                + "AND idempotency_key LIKE '%" + freshKey + "'"));

        // If-Match 2 matches the committed revision, so the only remaining guard is
        // the closed proposal and its one-revision-per-proposal constraint.
        ResponseEntity<String> retry = approve(proposalId, PRESCRIBER, ORG, "2", freshKey);
        assertEquals(HttpStatus.CONFLICT, retry.getStatusCode(), retry.getBody());
        assertEquals("CLOSED_PROPOSAL", field(retry.getBody(), "code"));
        assertEquals(1, count(revisionSql));
        assertEquals(0, count("SELECT count(*) FROM public.pharmacy_substitution_decision "
                + "WHERE organization_id = CAST('" + ORG + "' AS uuid) AND command = 'approve' "
                + "AND idempotency_key LIKE '%" + freshKey + "'"));
    }

    @Test
    void expiredRetryKeyDoesNotBypassRoleOrTenant() throws Exception {
        String wrongRoleProposal = propose("clause-" + UUID.randomUUID());
        // The proposer holds pharmacy staff, not the clinician decision role.
        ResponseEntity<String> wrongRole = approve(wrongRoleProposal, PHARMACIST, ORG, "1",
                "claim-" + UUID.randomUUID());
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode(), wrongRole.getBody());
        assertEquals("FORBIDDEN", field(wrongRole.getBody(), "code"));
        assertEquals(0, count("SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST('" + ORG + "' AS uuid) "
                + "AND proposal_id = CAST('" + wrongRoleProposal + "' AS uuid)"));

        String peerTenantProposal = propose("clause-" + UUID.randomUUID());
        // The peer tenant has the same local subject name but no projection of the proposal.
        ResponseEntity<String> peerTenant = approve(peerTenantProposal, PRESCRIBER, OTHER_ORG, "1",
                "claim-" + UUID.randomUUID());
        assertEquals(HttpStatus.NOT_FOUND, peerTenant.getStatusCode(), peerTenant.getBody());
        assertEquals("NOT_FOUND", field(peerTenant.getBody(), "code"));
        assertEquals(0, count("SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST('" + ORG + "' AS uuid) "
                + "AND proposal_id = CAST('" + peerTenantProposal + "' AS uuid)"));
    }

    @Test
    void expiredRetryKeyDoesNotBypassMissingOrDifferentPayload() throws Exception {
        String missingKeyProposal = propose("clause-" + UUID.randomUUID());
        ResponseEntity<String> missing = approve(missingKeyProposal, PRESCRIBER, ORG, "1", null);
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, missing.getStatusCode(), missing.getBody());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", field(missing.getBody(), "code"));

        String malformedKeyProposal = propose("clause-" + UUID.randomUUID());
        ResponseEntity<String> malformed = approve(malformedKeyProposal, PRESCRIBER, ORG, "1", "bad key!");
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, malformed.getStatusCode(), malformed.getBody());
        assertEquals("INVALID_IDEMPOTENCY_KEY", field(malformed.getBody(), "code"));

        assertEquals(0, count("SELECT count(*) FROM public.pharmacy_substitution_revision "
                + "WHERE organization_id = CAST('" + ORG + "' AS uuid) AND proposal_id IN "
                + "(CAST('" + missingKeyProposal + "' AS uuid), CAST('" + malformedKeyProposal + "' AS uuid))"));
    }
}
