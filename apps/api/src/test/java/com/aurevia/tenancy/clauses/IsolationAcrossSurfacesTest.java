package com.aurevia.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.events.DeliveryStore;
import com.aurevia.events.JdbcDeliveryStore;
import com.aurevia.tenant.TenantContext;
import com.aurevia.tenancy.FacilityService;

/**
 * G0 isolation evidence for ARC-02 and IAM-01 across every API surface that
 * exists today. Two synthetic organizations reuse the same local identifiers
 * (facility code {@code MAIN}, MRN {@code MRN-SHARED}, order code
 * {@code CBC-SHARED}, file name {@code shared-scan.pdf} and delivery key
 * {@code ORDER-CODE-SHARED}). Each existing surface must conceal the peer
 * tenant's record, and a missing tenant context must fail closed.
 *
 * <p>The tenant export surface has no production implementation yet, so its
 * acceptance test is present but disabled with an explicit gap reason (see
 * {@code docs/gates/g0-isolation-surfaces.md}). No production code is added by
 * this suite; it only exercises existing entry points with synthetic data.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_isolation_surfaces_it")
@ActiveProfiles("synthetic-auth")
class IsolationAcrossSurfacesTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String UNKNOWN = "99999999-9999-9999-9999-999999999999";
    static final String SHARED_MRN = "MRN-SHARED";
    static final String SHARED_ORDER_CODE = "CBC-SHARED";
    static final String SHARED_FILE_NAME = "shared-scan.pdf";
    static final String SHARED_EVENT_KEY = "ORDER-CODE-SHARED";
    static final String DIGEST = "a".repeat(64);
    static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_isolation_surfaces_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DeliveryStore deliveryStore;

    @Autowired
    JdbcDeliveryStore jdbcDeliveryStore;

    @Autowired
    FacilityService facilityService;

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

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
            statement.execute("DROP DATABASE IF EXISTS orevia_isolation_surfaces_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_isolation_surfaces_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "reception-b", "reception");
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedUser(statement, ORG_A, "worker-a", "worker");
            seedUser(statement, ORG_B, "worker-b", "worker");
            seedUser(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedUser(statement, ORG_B, "auditor-b", "privacy_auditor");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', 'MAIN', 'Alpha Main')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', 'MAIN', 'Beta Main')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', 'ONLY-B', 'Beta Only')");
            // Both tenants reuse the same issuer-scoped MRN. Seeded once so the
            // shared local identifier is identical across every surface test.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'reception-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT_A + "', 1, '" + SHARED_MRN
                    + "', 'CLINIC', 'Shared Alpha', 'shared alpha', DATE '1990-05-20')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("SET app.actor = 'reception-b'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_B + "', '" + PATIENT_B + "', 1, '" + SHARED_MRN
                    + "', 'CLINIC', 'Shared Beta', 'shared beta', DATE '1990-05-20')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_isolation_surfaces_it WITH (FORCE)");
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
                                           String subject, String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String uri, String subject, String organization) {
        return request(HttpMethod.GET, uri, null, subject, organization);
    }

    private ResponseEntity<String> post(String uri, String body, String subject, String organization) {
        return request(HttpMethod.POST, uri, body, subject, organization);
    }

    private ResponseEntity<byte[]> content(String uri, String subject, String organization) {
        return rest.get().uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(byte[].class);
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

    /**
     * Bind tenant context on the calling thread, as the HTTP interceptor does,
     * for direct store reads that bypass the HTTP entry point.
     */
    private <T> T as(String organization, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(organization, subject, "ISOLATION_EVIDENCE", "surfaces-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private String createAuthorization(String destination, String subject, String organization) {
        String body = "{\"orderCode\":\"" + SHARED_ORDER_CODE + "\",\"destinationOrg\":\"" + destination
                + "\",\"purpose\":\"lab review\",\"maxDurationMinutes\":60,\"validForDays\":2}";
        ResponseEntity<String> response = post("/api/v1/exchange/authorizations", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String createOrder(String patient, String destination, String grant,
                               String subject, String organization) {
        String body = "{\"patientId\":\"" + patient + "\",\"encounterRef\":\"ENC-SHARED\","
                + "\"orderCode\":\"" + SHARED_ORDER_CODE + "\",\"priority\":\"routine\","
                + "\"clinicalQuestion\":\"Synthetic shared-code question\",\"destinationOrg\":\""
                + destination + "\",\"purpose\":\"lab review\",\"authorizationId\":\"" + grant
                + "\",\"responsibleClinician\":\"dr-shared\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":30}";
        ResponseEntity<String> response = post("/api/v1/orders", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String linkFile(String chart, String subject, String organization) {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chart
                + "\",\"fileName\":\"" + SHARED_FILE_NAME
                + "\",\"mediaType\":\"application/pdf\",\"contentBase64\":\"" + ENCODED
                + "\",\"rotationDegrees\":0}";
        ResponseEntity<String> response = post("/api/v1/documents/associations", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String renderPdf(String chart, String subject, String organization) {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chart
                + "\",\"locale\":\"en\",\"title\":\"Shared title\",\"lines\":[\"Synthetic line\"],"
                + "\"sourceSystem\":\"SYNTHETIC-LAB\",\"sourceId\":\"REQ-SHARED\","
                + "\"effectiveAt\":\"2026-11-19T10:00:00Z\",\"rotationDegrees\":0,"
                + "\"sourceAssociationId\":null}";
        ResponseEntity<String> response = post("/api/v1/documents/rendered", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String publishEvent(String subject, String organization) {
        String body = "{\"aggregateId\":\"" + UUID.randomUUID()
                + "\",\"aggregateVersion\":1,\"sender\":\"lab-partner\","
                + "\"eventType\":\"observation.result\",\"namespace\":\"partner-lab\","
                + "\"payloadDigest\":\"" + DIGEST + "\",\"idempotencyKey\":\"" + SHARED_EVENT_KEY + "\"}";
        ResponseEntity<String> response = post("/api/v1/delivery/events", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "eventId");
    }

    @Test
    void apiReadsAndWritesStayTenantScopedWithTheSameFacilityCode() {
        ResponseEntity<String> alpha = get("/api/v1/facilities/MAIN", "admin-a", ORG_A);
        ResponseEntity<String> beta = get("/api/v1/facilities/MAIN", "admin-b", ORG_B);
        assertEquals(HttpStatus.OK, alpha.getStatusCode());
        assertEquals(HttpStatus.OK, beta.getStatusCode());
        assertTrue(alpha.getBody().contains(ORG_A));
        assertFalse(alpha.getBody().contains(ORG_B));
        assertTrue(beta.getBody().contains(ORG_B));
        assertFalse(beta.getBody().contains(ORG_A));

        // A code that exists only in tenant B must be concealed from tenant A.
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/facilities/ONLY-B", "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK,
                get("/api/v1/facilities/ONLY-B", "admin-b", ORG_B).getStatusCode());

        // Both tenants may write the same new local code; neither write crosses.
        String body = "{\"code\":\"SHARED-WARD\",\"name\":\"Shared ward\"}";
        assertEquals(HttpStatus.CREATED, post("/api/v1/facilities", body, "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CREATED, post("/api/v1/facilities", body, "admin-b", ORG_B).getStatusCode());
        String wardA = get("/api/v1/facilities/SHARED-WARD", "admin-a", ORG_A).getBody();
        String wardB = get("/api/v1/facilities/SHARED-WARD", "admin-b", ORG_B).getBody();
        assertTrue(wardA.contains(ORG_A));
        assertFalse(wardA.contains(ORG_B));
        assertTrue(wardB.contains(ORG_B));
        assertFalse(wardB.contains(ORG_A));
    }

    @Test
    void patientSearchNeverReturnsThePeerTenantsRecordWithTheSameMrn() {
        String patientA = PATIENT_A;
        String patientB = PATIENT_B;

        ResponseEntity<String> resultsA = get("/api/v1/patients?name=shared", "reception-a", ORG_A);
        ResponseEntity<String> resultsB = get("/api/v1/patients?name=shared", "reception-b", ORG_B);
        assertEquals(HttpStatus.OK, resultsA.getStatusCode());
        assertEquals(HttpStatus.OK, resultsB.getStatusCode());
        assertTrue(resultsA.getBody().contains(patientA));
        assertTrue(resultsA.getBody().contains("Shared Alpha"));
        assertFalse(resultsA.getBody().contains(patientB));
        assertFalse(resultsA.getBody().contains("Shared Beta"));
        assertTrue(resultsB.getBody().contains(patientB));
        assertTrue(resultsB.getBody().contains("Shared Beta"));
        assertFalse(resultsB.getBody().contains(patientA));

        // The peer tenant's record id is a uniform 404, never a cross-tenant read.
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/patients/" + patientB, "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/patients/" + patientA, "reception-b", ORG_B).getStatusCode());
    }

    @Test
    void ordersWithTheSameLocalCodeStayTenantScoped() {
        String patientA = PATIENT_A;
        String patientB = PATIENT_B;
        String grantA = createAuthorization(ORG_B, "admin-a", ORG_A);
        String grantB = createAuthorization(ORG_A, "admin-b", ORG_B);
        String orderA = createOrder(patientA, ORG_B, grantA, "clinician-a", ORG_A);
        String orderB = createOrder(patientB, ORG_A, grantB, "clinician-b", ORG_B);
        assertNotEquals(orderA, orderB);

        assertEquals(HttpStatus.OK, get("/api/v1/orders/" + orderA, "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/v1/orders/" + orderB, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/v1/orders/" + orderA, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/v1/orders/" + orderB, "clinician-a", ORG_A).getStatusCode());
        assertTrue(get("/api/v1/orders/" + orderA, "clinician-a", ORG_A).getBody()
                .contains(SHARED_ORDER_CODE));
        String listA = get("/api/v1/orders", "clinician-a", ORG_A).getBody();
        String listB = get("/api/v1/orders", "clinician-b", ORG_B).getBody();
        assertTrue(listA.contains(orderA));
        assertFalse(listA.contains(orderB));
        assertTrue(listB.contains(orderB));
        assertFalse(listB.contains(orderA));
    }

    @Test
    void filesAndRendersWithTheSameNameAreConcealedAcrossTenants() {
        String chartA = PATIENT_A;
        String chartB = PATIENT_B;
        String fileA = linkFile(chartA, "clinician-a", ORG_A);
        String fileB = linkFile(chartB, "clinician-b", ORG_B);
        String renderA = renderPdf(chartA, "clinician-a", ORG_A);
        String renderB = renderPdf(chartB, "clinician-b", ORG_B);
        assertNotEquals(fileA, fileB);
        assertNotEquals(renderA, renderB);

        assertEquals(SHARED_FILE_NAME,
                field(get("/api/v1/documents/associations/" + fileA, "clinician-a", ORG_A).getBody(), "fileName"));
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/associations/" + fileA, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/associations/" + fileB, "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                content("/api/v1/documents/associations/" + fileA + "/content", "clinician-b", ORG_B)
                        .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/rendered/" + renderA, "clinician-b", ORG_B).getStatusCode());
        ResponseEntity<byte[]> deniedBytes = content(
                "/api/v1/documents/rendered/" + renderA + "/content", "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, deniedBytes.getStatusCode());
        assertEquals(HttpStatus.OK,
                content("/api/v1/documents/associations/" + fileA + "/content", "clinician-a", ORG_A)
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                content("/api/v1/documents/rendered/" + renderA + "/content", "clinician-a", ORG_A)
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                get("/api/v1/documents/rendered/" + renderB, "clinician-b", ORG_B).getStatusCode());
    }

    @Test
    void workerAndOutboxProcessingStaysTenantScopedWithTheSameEventKey() {
        assertTrue(deliveryStore instanceof JdbcDeliveryStore,
                "the production delivery store must be the PostgreSQL adapter");
        String eventA = publishEvent("worker-a", ORG_A);
        String eventB = publishEvent("worker-b", ORG_B);
        assertNotEquals(eventA, eventB);

        assertTrue(as(ORG_B, "worker-b", () -> jdbcDeliveryStore.outboxEvent(ORG_B, eventA)).isEmpty());
        assertTrue(as(ORG_A, "worker-a", () -> jdbcDeliveryStore.outboxEvent(ORG_A, eventB)).isEmpty());
        assertEquals(eventA, as(ORG_A, "worker-a", () -> jdbcDeliveryStore
                .byIdempotencyKey(ORG_A, "lab-partner", "observation.result", SHARED_EVENT_KEY)
                .orElseThrow().eventId()));
        assertEquals(eventB, as(ORG_B, "worker-b", () -> jdbcDeliveryStore
                .byIdempotencyKey(ORG_B, "lab-partner", "observation.result", SHARED_EVENT_KEY)
                .orElseThrow().eventId()));
        assertTrue(as(ORG_B, "auditor-b", () -> jdbcDeliveryStore.deadLetters(ORG_B)).stream()
                .noneMatch(entry -> eventA.equals(entry.eventId())));

        // A worker of tenant B cannot advance tenant A's outbox event.
        assertEquals(HttpStatus.NOT_FOUND,
                rest.post().uri("/api/v1/delivery/events/" + eventA + "/sent")
                        .headers(httpHeaders -> httpHeaders.addAll(headers("worker-b", ORG_B)))
                        .header("If-Match", "\"1\"")
                        .retrieve().onStatus(status -> true, (req, response) -> { })
                        .toEntity(String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                rest.post().uri("/api/v1/delivery/events/" + eventA + "/sent")
                        .headers(httpHeaders -> httpHeaders.addAll(headers("worker-a", ORG_A)))
                        .header("If-Match", "\"1\"")
                        .retrieve().onStatus(status -> true, (req, response) -> { })
                        .toEntity(String.class).getStatusCode());
    }

    @Test
    void missingTenantContextFailsClosedOnEverySurface() {
        // No authenticated subject: every existing surface refuses before SQL.
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/facilities/MAIN", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/patients?name=shared", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/documents/rendered/" + UNKNOWN + "/content", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/orders", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/delivery/dead-letters", null, ORG_A).getStatusCode());

        // No explicit active organization: fail closed instead of first membership.
        assertEquals(422, get("/api/v1/facilities/MAIN", "admin-a", null).getStatusCode().value());
        assertEquals(422, get("/api/v1/patients?name=shared", "reception-a", null).getStatusCode().value());
        assertEquals(422,
                get("/api/v1/documents/associations/" + UNKNOWN, "clinician-a", null).getStatusCode().value());
        assertEquals(422, get("/api/v1/orders", "clinician-a", null).getStatusCode().value());
        assertEquals(422, get("/api/v1/delivery/dead-letters", "worker-a", null).getStatusCode().value());

        // The fail-closed rule applies to writes too, not only reads. A state-changing
        // request without a subject or without an explicit organization must be refused
        // before any tenant-scoped SQL runs.
        String newFacility = "{\"code\":\"NO-CONTEXT\",\"name\":\"No context\"}";
        assertEquals(HttpStatus.UNAUTHORIZED,
                post("/api/v1/facilities", newFacility, null, ORG_A).getStatusCode());
        assertEquals(422,
                post("/api/v1/facilities", newFacility, "admin-a", null).getStatusCode().value());

        // A valid subject asserting a foreign organization has no active membership.
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/v1/facilities/MAIN", "admin-a", ORG_B).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/v1/patients?name=shared", "reception-a", ORG_B).getStatusCode());

        // A direct service call with no thread context cannot open a tenant transaction.
        TenantContext.clear();
        assertThrows(IllegalStateException.class, () -> facilityService.get("MAIN"));
    }

    /**
     * OPS-07 acceptance: a tenant export must carry only the active tenant's
     * records, manifest, checksums, authorized documents, provenance and audit.
     * Disabled until a tenant export surface exists — see the gap note in
     * {@code docs/gates/g0-isolation-surfaces.md}.
     */
    @Test
    @Disabled("GAP: OPS-07 tenant export endpoint, manifest, checksums and authorized-document "
            + "selection do not exist. Only an in-scope messaging-governance export exists. "
            + "No production code is added by this test.")
    void tenantExportNeverIncludesAnotherTenantsRecords() {
        String patientA = PATIENT_A;
        linkFile(patientA, "clinician-a", ORG_A);
        linkFile(PATIENT_B, "clinician-b", ORG_B);

        ResponseEntity<String> created = post("/api/v1/tenant-exports",
                "{\"scope\":\"chart\",\"format\":\"json\",\"includeDocuments\":true}", "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String exportId = field(created.getBody(), "id");
        assertEquals(ORG_A, field(created.getBody(), "organizationId"));

        String manifest = get("/api/v1/tenant-exports/" + exportId + "/manifest", "admin-a", ORG_A).getBody();
        assertTrue(manifest.contains("schemaVersion"));
        assertTrue(manifest.contains("sha256"));
        assertTrue(manifest.contains(patientA));
        assertTrue(manifest.contains(SHARED_FILE_NAME));
        assertFalse(manifest.contains(PATIENT_B));
        assertFalse(manifest.contains("Shared Beta"));
        assertFalse(manifest.contains(ORG_B));

        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/tenant-exports/" + exportId + "/manifest", "admin-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/tenant-exports/" + exportId + "/manifest", null, ORG_A).getStatusCode());
        assertEquals(422,
                get("/api/v1/tenant-exports/" + exportId + "/manifest", "admin-a", null).getStatusCode().value());
    }
}
