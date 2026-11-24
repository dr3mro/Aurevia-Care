package com.aurevia.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.aurevia.tenant.TenantContext;

/**
 * ARC-02 clause 6 acceptance over the real exchange HTTP entry points.
 *
 * <p>An order may cross an organization boundary only through a grant that the
 * sender explicitly approved for that recipient, order code and purpose. The
 * receiver gets a bounded work item bound to the released snapshot fingerprint,
 * never the sender's chart. Every fixture is synthetic: three organizations,
 * two reused MRNs and two subjects per organization with distinct roles. No
 * production code, existing test, migration or contract is changed by this
 * suite.</p>
 *
 * <p>Raw-SQL assertions are wrapped in {@link #as(String, String, Supplier)} and
 * a tenant-guarded {@link TransactionTemplate}: forced RLS filters every row
 * when {@code app.current_org} is unbound, so an unguarded query would report a
 * false zero instead of the intended row count.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_cross_tenant_exchange_it")
@ActiveProfiles("synthetic-auth")
class CrossTenantExchangeUsesExplicitVerifiedSenderTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String SHARED_MRN = "MRN-SHARED";
    static final String MRN_ISSUER = "CLINIC";
    static final String ORDER_CODE = "CBC-EXPLICIT-GRANT";
    static final String PURPOSE = "lab review";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_cross_tenant_exchange_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create the isolated database and seed the synthetic organizations, roles and patients. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_cross_tenant_exchange_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_cross_tenant_exchange_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "reception-b", "reception");
            // A third organization is the wrong recipient for a grant to B. Only
            // the organization row is needed: no user acts on its behalf.
            statement.execute("SET app.current_org = '" + ORG_C + "'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG_C + "', 'Org C')");
            // Both patients carry the same issuer-scoped MRN so the receiver can
            // verify the projected identity against its own local record.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'reception-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT_A + "', 1, '" + SHARED_MRN
                    + "', '" + MRN_ISSUER + "', 'Alpha Shared', 'alpha shared', DATE '1990-05-20')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("SET app.actor = 'reception-b'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_B + "', '" + PATIENT_B + "', 1, '" + SHARED_MRN
                    + "', '" + MRN_ISSUER + "', 'Beta Shared', 'beta shared', DATE '1990-05-20')");
        }
    }

    /** Drop the isolated database so the fixture cannot leak across runs. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_cross_tenant_exchange_it WITH (FORCE)");
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

    private HttpHeaders headers(String subject, String organization) {
        return headers(subject, organization, null);
    }

    private HttpHeaders headers(String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String uri, String subject, String organization) {
        return request(HttpMethod.GET, uri, null, subject, organization, null);
    }

    private ResponseEntity<String> post(String uri, String body, String subject, String organization) {
        return request(HttpMethod.POST, uri, body, subject, organization, null);
    }

    private ResponseEntity<String> post(String uri, String body, String subject,
                                        String organization, String correlation) {
        return request(HttpMethod.POST, uri, body, subject, organization, correlation);
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

    /** Bind tenant context as the HTTP interceptor does, for guarded direct SQL reads. */
    private <T> T as(String organization, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(organization, subject, "EXCHANGE_EVIDENCE", "exchange-clauses-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private int countAs(String organization, String subject, String sql, Object... arguments) {
        return as(organization, subject, () -> transactions.execute(
                status -> jdbc.queryForObject(sql, Integer.class, arguments)));
    }

    private int ordersWithMarkerIn(String organization, String subject, String marker) {
        return countAs(organization, subject,
                "SELECT count(*) FROM public.exchange_order WHERE clinical_question = ?", marker);
    }

    private int disclosuresWithMarker(String marker) {
        return countAs(ORG_A, "clinician-a", "SELECT count(*) FROM public.exchange_disclosure d "
                + "JOIN public.exchange_order o ON o.organization_id = d.organization_id "
                + "AND o.id = d.order_id WHERE o.clinical_question = ?", marker);
    }

    private String createAuthorization(String destination, String subject, String organization) {
        String body = "{\"orderCode\":\"" + ORDER_CODE + "\",\"destinationOrg\":\"" + destination
                + "\",\"purpose\":\"" + PURPOSE + "\",\"maxDurationMinutes\":60,\"validForDays\":2}";
        ResponseEntity<String> response = post("/api/v1/exchange/authorizations", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private static String orderBody(String patient, String destination, String grant,
                                    String purpose, String question) {
        return "{\"patientId\":\"" + patient + "\",\"encounterRef\":\"ENC-EXPLICIT\","
                + "\"orderCode\":\"" + ORDER_CODE + "\",\"priority\":\"routine\","
                + "\"clinicalQuestion\":\"" + question + "\",\"destinationOrg\":\"" + destination
                + "\",\"purpose\":\"" + purpose + "\",\"authorizationId\":\"" + grant
                + "\",\"responsibleClinician\":\"dr-explicit\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":30}";
    }

    private String createOrder(String patient, String destination, String grant, String purpose,
                               String question, String subject, String organization, String correlation) {
        ResponseEntity<String> response = post("/api/v1/orders",
                orderBody(patient, destination, grant, purpose, question),
                subject, organization, correlation);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /**
     * A sender's explicit grant to one named recipient releases only a bounded
     * projection. The receiver binds its own verified patient and never reads the
     * sender's order, and the audit trail carries no clinical payload.
     */
    @Test
    void onlyDisclosesThroughVerifiedGrantToNamedRecipient() {
        String correlation = UUID.randomUUID().toString();
        String marker = "Positive bounded disclosure " + UUID.randomUUID();
        String grant = createAuthorization(ORG_B, "admin-a", ORG_A);
        String order = createOrder(PATIENT_A, ORG_B, grant, PURPOSE, marker,
                "clinician-a", ORG_A, correlation);

        ResponseEntity<String> ownerView = get("/api/v1/orders/" + order, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, ownerView.getStatusCode(), ownerView.getBody());
        String fingerprint = field(ownerView.getBody(), "snapshotFingerprint");
        String workItem = field(ownerView.getBody(), "workItemId");
        assertFalse(fingerprint.isBlank(), ownerView.getBody());
        assertFalse(workItem.isBlank(), ownerView.getBody());
        assertFalse(field(ownerView.getBody(), "snapshotId").isBlank(), ownerView.getBody());
        assertFalse(field(ownerView.getBody(), "disclosureId").isBlank(), ownerView.getBody());
        // Positive control for the disclosure-count query used by the negatives:
        // without a known row the zero-count assertions could pass vacuously.
        assertEquals(1, disclosuresWithMarker(marker));

        String projected = get("/api/v1/exchange/work-items", "clinician-b", ORG_B).getBody();
        assertTrue(projected.contains(workItem), projected);
        assertTrue(projected.contains(marker), projected);
        assertTrue(projected.contains(fingerprint), projected);
        assertTrue(projected.contains(ORG_A), projected);
        assertTrue(projected.contains(PATIENT_A), projected);
        assertTrue(projected.contains(SHARED_MRN), projected);
        // Only exchange fields are projected: no snapshot, disclosure or chart content.
        assertFalse(projected.contains("snapshotId"), projected);
        assertFalse(projected.contains("disclosureId"), projected);
        assertFalse(projected.contains("\"content\""), projected);

        // The work item grants no general chart access to the receiving tenant.
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/orders/" + order, "clinician-b", ORG_B).getStatusCode());

        String verification = "{\"localPatientId\":\"" + PATIENT_B + "\",\"localMrn\":\"" + SHARED_MRN
                + "\",\"mrnIssuer\":\"" + MRN_ISSUER
                + "\",\"attestation\":\"Synthetic receiver identifier check\"}";
        ResponseEntity<String> verified = post("/api/v1/exchange/work-items/" + workItem + "/verify-patient",
                verification, "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertTrue(verified.getBody().contains("\"patientVerified\":true"), verified.getBody());
        assertTrue(verified.getBody().contains("\"state\":\"ready\""), verified.getBody());
        assertEquals(PATIENT_B, field(verified.getBody(), "localPatientId"));
        assertEquals(fingerprint, field(verified.getBody(), "fingerprint"));

        assertTrue(countAs(ORG_A, "clinician-a",
                "SELECT count(*) FROM public.audit_event WHERE correlation_id = ?", correlation) > 0);
        assertEquals(0, countAs(ORG_A, "clinician-a",
                "SELECT count(*) FROM public.audit_event WHERE correlation_id = ? AND "
                + "(coalesce(action,'') || coalesce(resource_type,'') || coalesce(resource_id,'') "
                + "|| coalesce(purpose,'') || coalesce(scope,'') || coalesce(outcome,'')) LIKE ?",
                correlation, "%" + marker + "%"));
    }

    /**
     * A grant authorizes one recipient, purpose and payload class. A release that
     * changes either the purpose or the recipient is refused and leaves no row.
     */
    @Test
    void refusesOrderWhenGrantRecipientOrPurposeDoesNotMatch() {
        String grant = createAuthorization(ORG_B, "admin-a", ORG_A);
        String wrongPurposeMarker = "Wrong purpose " + UUID.randomUUID();
        String wrongRecipientMarker = "Wrong recipient " + UUID.randomUUID();

        ResponseEntity<String> wrongPurpose = post("/api/v1/orders",
                orderBody(PATIENT_A, ORG_B, grant, "radiology review", wrongPurposeMarker),
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, wrongPurpose.getStatusCode(), wrongPurpose.getBody());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", field(wrongPurpose.getBody(), "code"));

        ResponseEntity<String> wrongRecipient = post("/api/v1/orders",
                orderBody(PATIENT_A, ORG_C, grant, PURPOSE, wrongRecipientMarker),
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, wrongRecipient.getStatusCode(), wrongRecipient.getBody());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", field(wrongRecipient.getBody(), "code"));

        assertEquals(0, ordersWithMarkerIn(ORG_A, "clinician-a", wrongPurposeMarker));
        assertEquals(0, ordersWithMarkerIn(ORG_A, "clinician-a", wrongRecipientMarker));
        assertEquals(0, disclosuresWithMarker(wrongPurposeMarker));
        assertEquals(0, disclosuresWithMarker(wrongRecipientMarker));
    }

    /**
     * Forced RLS makes another tenant's grant invisible, so a receiver cannot
     * reuse it, and a peer order id is concealed rather than disclosed.
     */
    @Test
    void refusesCrossTenantGrantAndConcealsPeerOrder() {
        String ownerMarker = "Owner order " + UUID.randomUUID();
        String foreignMarker = "Foreign grant attempt " + UUID.randomUUID();
        String grantA = createAuthorization(ORG_B, "admin-a", ORG_A);
        String orderA = createOrder(PATIENT_A, ORG_B, grantA, PURPOSE, ownerMarker,
                "clinician-a", ORG_A, null);

        ResponseEntity<String> foreignGrant = post("/api/v1/orders",
                orderBody(PATIENT_B, ORG_B, grantA, PURPOSE, foreignMarker),
                "clinician-b", ORG_B);
        assertEquals(HttpStatus.FORBIDDEN, foreignGrant.getStatusCode(), foreignGrant.getBody());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", field(foreignGrant.getBody(), "code"));
        assertEquals(0, ordersWithMarkerIn(ORG_B, "clinician-b", foreignMarker));

        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/orders/" + orderA, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.OK,
                get("/api/v1/orders/" + orderA, "clinician-a", ORG_A).getStatusCode());
        assertEquals(1, ordersWithMarkerIn(ORG_A, "clinician-a", ownerMarker));
    }

    /**
     * Tenant context comes from the authenticated subject and an explicit
     * organization selection; a missing one is refused before any SQL runs.
     */
    @Test
    void failsClosedWithoutSubjectOrActiveOrganizationOnExchange() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/exchange/work-items", null, ORG_A).getStatusCode());
        assertEquals(422,
                get("/api/v1/exchange/work-items", "clinician-a", null).getStatusCode().value());

        String authorizationBody = "{\"orderCode\":\"NO-CONTEXT-CODE\",\"destinationOrg\":\"" + ORG_B
                + "\",\"purpose\":\"" + PURPOSE + "\",\"maxDurationMinutes\":60,\"validForDays\":2}";
        assertEquals(HttpStatus.UNAUTHORIZED,
                post("/api/v1/exchange/authorizations", authorizationBody, null, ORG_A).getStatusCode());
        assertEquals(422, post("/api/v1/exchange/authorizations", authorizationBody, "admin-a", null)
                .getStatusCode().value());

        String orderMarker = "No context " + UUID.randomUUID();
        String orderBody = orderBody(PATIENT_A, ORG_B, UUID.randomUUID().toString(), PURPOSE, orderMarker);
        assertEquals(HttpStatus.UNAUTHORIZED,
                post("/api/v1/orders", orderBody, null, ORG_A).getStatusCode());
        assertEquals(422,
                post("/api/v1/orders", orderBody, "clinician-a", null).getStatusCode().value());

        assertEquals(0, ordersWithMarkerIn(ORG_A, "clinician-a", orderMarker));
        assertEquals(0, countAs(ORG_A, "clinician-a",
                "SELECT count(*) FROM public.exchange_authorization WHERE order_code = ?",
                "NO-CONTEXT-CODE"));
    }
}
