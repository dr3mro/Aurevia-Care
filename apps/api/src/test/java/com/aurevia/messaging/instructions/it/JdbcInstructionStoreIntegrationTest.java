package com.aurevia.messaging.instructions.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.messaging.instructions.InstructionContract.Fact;
import com.aurevia.messaging.instructions.InstructionContract.FactType;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.Revision;
import com.aurevia.messaging.instructions.InstructionContract.State;
import com.aurevia.messaging.instructions.InstructionContract.Template;
import com.aurevia.messaging.instructions.JdbcInstructionStore;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-01, ARC-02, OPS-02 acceptance for the durable MSG-04 instruction store.
 *
 * <p>Every operation runs against a real PostgreSQL database with the production
 * migrations applied, so forced row-level security and the tenant-guarded
 * transaction are exercised rather than mocked. Direct store calls bind the
 * tenant context explicitly; the HTTP test drives the real controller so the
 * production chain controller -> service -> JDBC store is proven end to end.
 * All identifiers and content are synthetic; no patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_instruction_store_it")
@ActiveProfiles("synthetic-auth")
class JdbcInstructionStoreIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_REVISIONS = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String PATIENT_STATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
    private static final String PATIENT_LINK = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3";
    private static final String PATIENT_SHARED = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4";
    private static final String UNIT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CONNECTION = "conn-instruction-it";
    private static final String SUBJECT_A = "clinician-a";
    private static final String SUBJECT_B = "clinician-b";
    private static final Instant ISSUED = Instant.parse("2026-11-20T10:00:00Z");
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/aurevia_instruction_store_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    JdbcInstructionStore store;

    @LocalServerPort
    int port;

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_instruction_store_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_instruction_store_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, SUBJECT_A, "clinician");
            seedUser(statement, ORG_B, SUBJECT_B, "clinician");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = '" + SUBJECT_A + "'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-INSTRUCTION-IT', 'CLINIC', "
                    + "'Synthetic Instruction Patient', 'synthetic instruction patient', DATE '1990-05-20')");
            seedRelationship(statement, ORG_A, SUBJECT_A, "order-a");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("SET app.actor = '" + SUBJECT_B + "'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_B + "', '" + PATIENT + "', 1, 'MRN-INSTRUCTION-IT', 'CLINIC', "
                    + "'Synthetic Instruction Patient', 'synthetic instruction patient', DATE '1990-05-20')");
            seedRelationship(statement, ORG_B, SUBJECT_B, "order-b");
        }
    }

    private static void seedRelationship(Statement statement, String org, String subject, String reference)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.instruction_fact_unit_assignment "
                + "(organization_id, subject, unit_id) VALUES ('" + org + "', '" + subject + "', '"
                + UNIT + "')");
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, patient_id, unit_id, context, context_reference, "
                + "relationship_id, state, verified_by, verified_at) "
                + "VALUES ('" + org + "', '" + PATIENT + "', '" + UNIT + "', 'order', '"
                + reference + "', '" + org + "', 'active', '" + subject + "', now())");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_instruction_store_it WITH (FORCE)");
        }
    }

    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private ResponseEntity<String> request(String method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.valueOf(method))
                .uri(uri).headers(http -> http.addAll(headers));
        return (body == null ? spec : spec.body(body))
                .retrieve().onStatus(status -> true, (req, response) -> { }).toEntity(String.class);
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        int from = start + needle.length();
        if (json.charAt(from) == '"') {
            from++;
        }
        int end = json.indexOf('"', from);
        return json.substring(from, end);
    }

    private void bind(String org) {
        TenantContext.set(new TenantContext(org, org.equals(ORG_A) ? SUBJECT_A : SUBJECT_B,
                "MSG04", "instruction-store-it"));
    }

    private static Template template(String id, String unitId, String title) {
        return new Template(id, unitId, Kind.COLLECTION, title, "Please fast before 9am.", "en",
                1, "admin-a", ISSUED, false, ContextType.ORDER);
    }

    private static Revision revision(String threadId, int number, State state, String title,
                                     String patient) {
        return new Revision(threadId, number, "collection-v1", Kind.COLLECTION, title,
                "Please fast before 9am.", "en", SUBJECT_A, "clinician", UNIT, CONNECTION,
                patient, ISSUED, number == 1 ? ISSUED.plusSeconds(3600) : null,
                ContextType.ORDER, "order-1", List.of("doc-a"), state, null, null, null);
    }

    private static Fact fact(String threadId, int number, FactType type, String note, Instant at) {
        return new Fact(threadId, number, type, PATIENT, at, note);
    }

    @Test
    void templateSaveReadsBackAndFiltersByUnit() {
        bind(ORG_A);
        store.saveTemplate(template("collection-v1", UNIT, "Morning collection"));
        Optional<Template> found = store.approvedTemplate(UNIT, "collection-v1");
        assertTrue(found.isPresent());
        assertEquals("Morning collection", found.orElseThrow().title());
        assertEquals(Kind.COLLECTION, found.orElseThrow().kind());
        assertEquals(ContextType.ORDER, found.orElseThrow().contextType());
        assertTrue(store.approvedTemplate("cccccccc-cccc-cccc-cccc-cccccccccccc", "collection-v1").isEmpty());
        assertTrue(store.approvedTemplate(UNIT, "missing-template").isEmpty());
    }

    @Test
    void templateWithoutContextTypeRoundTripsAsNull() {
        bind(ORG_A);
        store.saveTemplate(new Template("general-v1", UNIT, Kind.GENERAL, "General", "Body",
                "en", 1, "admin-a", ISSUED, false, null));
        Optional<Template> found = store.approvedTemplate(UNIT, "general-v1");
        assertTrue(found.isPresent());
        assertNull(found.orElseThrow().contextType());
    }

    @Test
    void revisionsReadBackInAscendingOrderAndCurrentIsTheLatest() {
        bind(ORG_A);
        store.save(revision("thread-order-1", 1, State.SENT, "First", PATIENT_REVISIONS));
        store.save(revision("thread-order-1", 2, State.SENT, "Second", PATIENT_REVISIONS));
        List<Revision> history = store.history("thread-order-1");
        assertEquals(List.of(1, 2), history.stream().map(Revision::revision).toList());
        assertEquals(ISSUED.plusSeconds(3600), history.get(0).actionDueAt());
        assertNull(history.get(1).actionDueAt());
        assertEquals(2, store.current("thread-order-1").orElseThrow().revision());
        assertEquals(1, store.forPatient(PATIENT_REVISIONS).size());
        assertEquals(2, store.forPatient(PATIENT_REVISIONS).get(0).revision());
        assertTrue(store.current("missing-thread").isEmpty());
        assertTrue(store.history("missing-thread").isEmpty());
        assertTrue(store.forPatient("dddddddd-dddd-dddd-dddd-dddddddddddd").isEmpty());
    }

    @Test
    void factsAppendInOccurrenceOrder() {
        bind(ORG_A);
        store.append(fact("thread-facts-1", 1, FactType.SENT, "sent", ISSUED));
        store.append(fact("thread-facts-1", 1, FactType.DISPATCHED, null, ISSUED.plusSeconds(1)));
        List<Fact> facts = store.facts("thread-facts-1");
        assertEquals(List.of(FactType.SENT, FactType.DISPATCHED),
                facts.stream().map(Fact::type).toList());
        assertEquals("sent", facts.get(0).note());
        assertEquals(1, facts.get(1).revision());
        assertNull(facts.get(1).note());
        assertTrue(store.facts("missing-thread").isEmpty());
    }

    @Test
    void supersedeAndWithdrawUpdateStateAndLinksAndMissingThreadFailsClosed() {
        bind(ORG_A);
        store.save(revision("thread-state-1", 1, State.SENT, "First", PATIENT_STATE));
        store.save(revision("thread-state-1", 2, State.SENT, "Second", PATIENT_STATE));
        store.supersede("thread-state-1", 1, 2);
        Revision prior = store.history("thread-state-1").get(0);
        assertEquals(State.SUPERSEDED, prior.state());
        assertEquals(2, prior.supersededBy());
        assertEquals(State.SENT, store.history("thread-state-1").get(1).state());

        // A revision that does not exist on a known thread is a no-op, matching
        // the in-memory port: only an unknown thread is a 404.
        store.supersede("thread-state-1", 99, 100);
        assertEquals(State.SENT, store.current("thread-state-1").orElseThrow().state());

        store.withdraw("thread-state-1", 2, "cancelled by site");
        Revision withdrawn = store.current("thread-state-1").orElseThrow();
        assertEquals(State.WITHDRAWN, withdrawn.state());
        assertEquals("cancelled by site", withdrawn.withdrawalReason());

        ApiException supersedeMissing = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> store.supersede("missing-thread", 1, 2));
        assertEquals(404, supersedeMissing.status());
        ApiException withdrawMissing = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> store.withdraw("missing-thread", 1, "reason"));
        assertEquals(404, withdrawMissing.status());
    }

    @Test
    void replacementRevisionRetainsItsSupersededLink() {
        bind(ORG_A);
        Revision replacement = new Revision("thread-link-1", 2, null, Kind.COLLECTION, "Second", "New body",
                "ar", SUBJECT_A, "clinician", UNIT, CONNECTION, PATIENT_LINK, ISSUED, null,
                ContextType.ORDER, "order-1", List.of(), State.SENT, null, 1, null);
        store.save(replacement);
        Revision stored = store.current("thread-link-1").orElseThrow();
        assertEquals(1, stored.replacesRevision());
        assertTrue(stored.attachmentIds().isEmpty());
    }

    @Test
    void identicalLocalIdsStayInvisibleAcrossTenants() {
        bind(ORG_A);
        store.saveTemplate(template("shared-template", UNIT, "Alpha"));
        store.save(revision("shared-thread", 1, State.SENT, "Alpha", PATIENT_SHARED));
        store.append(fact("shared-thread", 1, FactType.SENT, "alpha", ISSUED));

        bind(ORG_B);
        store.saveTemplate(template("shared-template", UNIT, "Beta"));
        store.save(revision("shared-thread", 1, State.SENT, "Beta", PATIENT_SHARED));
        store.append(fact("shared-thread", 1, FactType.SENT, "beta", ISSUED));
        assertEquals("Beta", store.approvedTemplate(UNIT, "shared-template").orElseThrow().title());
        assertEquals("Beta", store.current("shared-thread").orElseThrow().title());
        assertEquals(1, store.facts("shared-thread").size());
        assertEquals("beta", store.facts("shared-thread").get(0).note());

        bind(ORG_A);
        assertEquals("Alpha", store.approvedTemplate(UNIT, "shared-template").orElseThrow().title());
        assertEquals("Alpha", store.current("shared-thread").orElseThrow().title());
        assertEquals(1, store.facts("shared-thread").size());
        assertEquals("alpha", store.facts("shared-thread").get(0).note());
    }

    @Test
    void authoritativeFoldsAreTenantScoped() {
        bind(ORG_A);
        assertEquals(List.of("clinician"), store.activeRoles(SUBJECT_A));
        assertTrue(store.activeRoles("unknown-subject").isEmpty());
        assertTrue(store.verifiedPatientCharts("unknown-subject").isEmpty());
        assertTrue(store.staffAssignedToUnit(SUBJECT_A, UNIT));
        assertFalse(store.staffAssignedToUnit("unknown-subject", UNIT));
        assertTrue(store.connectionActive(CONNECTION, PATIENT, UNIT));
        assertFalse(store.connectionActive(CONNECTION, PATIENT, "cccccccc-cccc-cccc-cccc-cccccccccccc"));
        // unitId is an opaque contract key: a non-UUID value must be a structured
        // denial rather than an unhandled JDBC cast error.
        assertFalse(store.staffAssignedToUnit(SUBJECT_A, "unit-lab"));
        assertFalse(store.connectionActive(CONNECTION, PATIENT, "unit-lab"));
    }

    @Test
    void nonUuidUnitIdReturnsStructuredForbiddenThroughHttp() {
        String sendBody = "{\"patientId\":\"" + PATIENT + "\",\"connectionId\":\"" + CONNECTION
                + "\",\"unitId\":\"unit-lab\",\"templateId\":null,\"kind\":\"COLLECTION\","
                + "\"title\":\"Fasting\",\"text\":\"Fast before 9am\",\"language\":\"en\","
                + "\"contextType\":\"ORDER\",\"contextId\":\"order-1\",\"attachmentIds\":[]}";
        ResponseEntity<String> denied = request("POST", "/api/v1/patient-instructions", sendBody,
                SUBJECT_A, ORG_A, null);
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());
        assertTrue(denied.getBody().contains("\"code\":\"FORBIDDEN\""), denied.getBody());
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        ApiException denied = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> store.current("thread-1"));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
        assertEquals("no active tenant context", denied.getMessage());

        TenantContext.set(new TenantContext(null, SUBJECT_A, "MSG04", "instruction-store-it"));
        ApiException noOrg = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> store.facts("thread-1"));
        assertEquals(403, noOrg.status());
    }

    @Test
    void httpEntryPointPersistsThroughTheJdbcStoreAndIsolatesTenants() {
        String sendBody = "{\"patientId\":\"" + PATIENT + "\",\"connectionId\":\"" + CONNECTION
                + "\",\"unitId\":\"" + UNIT + "\",\"templateId\":null,\"kind\":\"COLLECTION\","
                + "\"title\":\"Fasting\",\"text\":\"Fast before 9am\",\"language\":\"en\","
                + "\"contextType\":\"ORDER\",\"contextId\":\"order-1\",\"attachmentIds\":[]}";
        ResponseEntity<String> sent = request("POST", "/api/v1/patient-instructions", sendBody,
                SUBJECT_A, ORG_A, null);
        assertEquals(201, sent.getStatusCode().value(), sent.getBody());
        String threadId = field(sent.getBody(), "threadId");
        assertFalse(threadId.isEmpty(), sent.getBody());
        assertEquals("SENT", field(sent.getBody(), "state"));

        // A fresh HTTP request rebinds the tenant from security headers; reading
        // the thread proves the revision and facts survived past the write.
        ResponseEntity<String> view = request("GET", "/api/v1/patient-instructions/" + threadId,
                null, SUBJECT_A, ORG_A, null);
        assertEquals(200, view.getStatusCode().value(), view.getBody());
        assertTrue(view.getBody().contains("\"type\":\"SENT\""), view.getBody());
        assertTrue(view.getBody().contains("\"type\":\"DISPATCHED\""), view.getBody());

        // The same local thread id in another tenant is invisible: forced RLS
        // confines the read to the caller's organization.
        ResponseEntity<String> otherTenant = request("GET", "/api/v1/patient-instructions/" + threadId,
                null, SUBJECT_B, ORG_B, null);
        assertEquals(404, otherTenant.getStatusCode().value(), otherTenant.getBody());

        ResponseEntity<String> withdrawn = request("POST",
                "/api/v1/patient-instructions/" + threadId + "/withdraw",
                "{\"reason\":\"cancelled by site\"}", SUBJECT_A, ORG_A, "\"1\"");
        assertEquals(200, withdrawn.getStatusCode().value(), withdrawn.getBody());
        assertEquals("WITHDRAWN", field(withdrawn.getBody(), "state"));

        bind(ORG_A);
        assertEquals(State.WITHDRAWN, store.current(threadId).orElseThrow().state());
    }
}
