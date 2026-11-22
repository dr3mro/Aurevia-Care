package com.aurevia.smartclinic.acceptance.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.documents.storage.ObjectKey;
import com.aurevia.smartclinic.documents.storage.ObjectStorePort;
import com.aurevia.smartclinic.documents.storage.StoredObject;
import com.aurevia.smartclinic.events.DeliveryStore;
import com.aurevia.smartclinic.events.JdbcDeliveryStore;
import com.aurevia.smartclinic.tenancy.FacilityService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Gate G0 and G1 acceptance evidence against a real PostgreSQL instance.
 *
 * <p>Requirements proved by this suite: ARC-02 (tenant isolation across API,
 * search, files, workers and exports, with fail-closed context), IAM-02
 * (a deactivated member is refused on the next protected request), IAM-04
 * (all-or-nothing duplicate consolidation), CLN-01 (competing bookings keep
 * one protected slot) and CLN-05 (a stale draft cannot overwrite a signed
 * note). Requirement IDs are named on each test method.</p>
 *
 * <p>Two synthetic organizations reuse exactly the same local identifiers —
 * facility code {@code MAIN}, issuer-scoped MRN {@code MRN-SHARED@CLINIC},
 * file name {@code shared-scan.pdf}, delivery key {@code ORDER-CODE-SHARED}
 * and export object alias {@code shared-export-object}. Each test asserts the
 * peer tenant's record is concealed (404, empty result or a response that
 * contains only the caller's organization). All fixtures are synthetic; no
 * real identifier, credential or patient data is used. This class only
 * exercises existing production entry points and adds no production code.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_platform_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PlatformAcceptanceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SHARED_MRN = "MRN-SHARED";
    private static final String SHARED_FILE = "shared-scan.pdf";
    private static final String SHARED_EVENT_KEY = "ORDER-CODE-SHARED";
    private static final String SHARED_EXPORT_OBJECT = "shared-export-object";
    private static final String DIGEST = "a".repeat(64);
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_platform_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DeliveryStore deliveryStore;

    @Autowired
    JdbcDeliveryStore jdbcDeliveryStore;

    @Autowired
    FacilityService facilityService;

    @Autowired
    ObjectStorePort objectStore;

    RestClient rest;

    /** Bind a real HTTP client to the randomly assigned server port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create a disposable database, apply every Flyway migration and seed two
     * tenants with identical local identifiers. The seed is shared by the G0
     * isolation and G1 clinic acceptance scenarios below.
     */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_platform_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_platform_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Drop the disposable acceptance database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_platform_it WITH (FORCE)");
        }
    }

    /**
     * Seed one organization with facilities, members, scheduling resources and
     * a shared-MRN patient. Both tenants receive byte-identical local codes so
     * the isolation assertions cannot pass by accident of different IDs.
     */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String suffix = ORG_A.equals(org) ? "a" : "b";
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Platform " + suffix + "')");
        seedUser(statement, org, "admin-" + suffix, "tenant_admin");
        seedUser(statement, org, "reception-" + suffix, "reception");
        seedUser(statement, org, "clinician-" + suffix, "clinician");
        seedUser(statement, org, "worker-" + suffix, "worker");
        seedUser(statement, org, "reviewer-" + suffix, "tenant_admin");
        seedUser(statement, org, "approver-" + suffix, "privacy_auditor");

        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + suffix + "'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Platform Main " + suffix + "', 'UTC')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'NEW', 30)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic clinician' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'room', 'ROOM-1', 'Synthetic room' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.resource_availability "
                + "(organization_id, resource_id, weekday, start_local, end_local) "
                + "SELECT '" + org + "', r.id, d, TIME '09:00', TIME '17:00' "
                + "FROM public.scheduling_resource r CROSS JOIN generate_series(1, 7) AS d");
        statement.execute("SET app.actor = 'reception-" + suffix + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + (ORG_A.equals(org) ? PATIENT_A : PATIENT_B)
                + "', 1, '" + SHARED_MRN + "', 'CLINIC', 'Shared " + (ORG_A.equals(org) ? "Alpha" : "Beta")
                + "', 'shared " + (ORG_A.equals(org) ? "alpha" : "beta") + "', DATE '1990-05-20')");
    }

    /** Insert an active membership and a bounded role grant for a synthetic subject. */
    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Build the synthetic subject and organization headers of one caller. */
    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Send an HTTP request through the real server, capturing every status. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        return request(method, uri, body, subject, organization, Map.of());
    }

    /** Send an HTTP request with extra headers such as {@code If-Match}. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, Map<String, String> extra) {
        HttpHeaders headers = headers(subject, organization);
        extra.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** GET a binary document body through the real server. */
    private ResponseEntity<byte[]> content(String uri, String subject, String organization) {
        return rest.get().uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(byte[].class);
    }

    /** Read a top-level JSON field without depending on a generated DTO. */
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

    /** Execute tenant-scoped SQL as the migration owner for fixture setup and assertions. */
    private static void execute(String organization, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
    }

    /** Read one scalar with a bound tenant context, rolling back the read transaction. */
    private static String scalar(String organization, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, subject);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next(), "expected a scalar row for " + sql);
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        }
    }

    /** Bind the transaction-local tenant GUCs exactly as the HTTP interceptor does. */
    private static void bind(Connection connection, String organization, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Bind TenantContext on the calling thread for direct store reads. */
    private <T> T as(String organization, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(organization, subject, "PLATFORM_EVIDENCE", "platform-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Compute a SHA-256 hex digest of synthetic bytes. */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** A future Monday at a wall-clock time inside the resource window. */
    private static Instant monday(int weeksAhead, int hour) {
        return LocalDate.now(ZoneOffset.UTC).plusWeeks(weeksAhead).with(DayOfWeek.MONDAY)
                .atTime(hour, 0).atZone(ZoneOffset.UTC).toInstant();
    }

    /** Book a synthetic appointment through the real scheduling entry point. */
    private ResponseEntity<String> book(String clinician, String room, Instant start,
                                        String patient, String subject, String organization) {
        String body = "{\"facilityCode\":\"MAIN\",\"patientId\":\"" + patient + "\","
                + "\"visitType\":\"NEW\",\"clinicianCode\":\"" + clinician + "\","
                + (room == null ? "" : "\"roomCode\":\"" + room + "\",")
                + "\"startsAt\":\"" + start + "\"}";
        return request(HttpMethod.POST, "/api/v1/appointments", body, subject, organization);
    }

    private static String patientOf(String organization) {
        return ORG_A.equals(organization) ? PATIENT_A : PATIENT_B;
    }

    private static String suffixOf(String organization) {
        return ORG_A.equals(organization) ? "a" : "b";
    }

    /**
     * ARC-02 / G0: API reads and writes and patient search are tenant scoped
     * even when both organizations use the same facility code and MRN.
     */
    @Test
    void apiAndSearchIsolateIdenticalLocalIdentifiers() {
        ResponseEntity<String> alpha = request(HttpMethod.GET, "/api/v1/facilities/MAIN",
                null, "admin-a", ORG_A);
        ResponseEntity<String> beta = request(HttpMethod.GET, "/api/v1/facilities/MAIN",
                null, "admin-b", ORG_B);
        assertEquals(HttpStatus.OK, alpha.getStatusCode(), alpha.getBody());
        assertEquals(HttpStatus.OK, beta.getStatusCode(), beta.getBody());
        assertTrue(alpha.getBody().contains(ORG_A));
        assertFalse(alpha.getBody().contains(ORG_B));
        assertTrue(beta.getBody().contains(ORG_B));
        assertFalse(beta.getBody().contains(ORG_A));

        ResponseEntity<String> searchA = request(HttpMethod.GET, "/api/v1/patients?name=shared",
                null, "reception-a", ORG_A);
        ResponseEntity<String> searchB = request(HttpMethod.GET, "/api/v1/patients?name=shared",
                null, "reception-b", ORG_B);
        assertEquals(HttpStatus.OK, searchA.getStatusCode());
        assertEquals(HttpStatus.OK, searchB.getStatusCode());
        assertTrue(searchA.getBody().contains(PATIENT_A));
        assertTrue(searchA.getBody().contains("Shared Alpha"));
        assertFalse(searchA.getBody().contains(PATIENT_B));
        assertFalse(searchA.getBody().contains("Shared Beta"));
        assertTrue(searchB.getBody().contains(PATIENT_B));
        assertTrue(searchB.getBody().contains("Shared Beta"));
        assertFalse(searchB.getBody().contains(PATIENT_A));

        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/v1/patients/" + PATIENT_B,
                null, "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/v1/patients/" + PATIENT_A,
                null, "reception-b", ORG_B).getStatusCode());

        String write = "{\"code\":\"SHARED-WARD\",\"name\":\"Shared ward\"}";
        assertEquals(HttpStatus.CREATED,
                request(HttpMethod.POST, "/api/v1/facilities", write, "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CREATED,
                request(HttpMethod.POST, "/api/v1/facilities", write, "admin-b", ORG_B).getStatusCode());
        String wardA = request(HttpMethod.GET, "/api/v1/facilities/SHARED-WARD", null, "admin-a", ORG_A).getBody();
        String wardB = request(HttpMethod.GET, "/api/v1/facilities/SHARED-WARD", null, "admin-b", ORG_B).getBody();
        assertTrue(wardA.contains(ORG_A));
        assertFalse(wardA.contains(ORG_B));
        assertTrue(wardB.contains(ORG_B));
        assertFalse(wardB.contains(ORG_A));
    }

    /**
     * ARC-02 / G0: files with the same name and worker outbox events with the
     * same idempotency key stay tenant scoped, and missing context fails closed.
     */
    @Test
    void filesAndWorkersIsolateIdenticalLocalIdentifiers() {
        assertTrue(deliveryStore instanceof JdbcDeliveryStore,
                "the production delivery store must be the PostgreSQL adapter");

        String fileA = linkFile(PATIENT_A, "clinician-a", ORG_A);
        String fileB = linkFile(PATIENT_B, "clinician-b", ORG_B);
        assertNotEquals(fileA, fileB);
        assertEquals(SHARED_FILE, field(request(HttpMethod.GET, "/api/v1/documents/associations/" + fileA,
                null, "clinician-a", ORG_A).getBody(), "fileName"));
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/documents/associations/" + fileA, null, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/documents/associations/" + fileB, null, "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                content("/api/v1/documents/associations/" + fileA + "/content", "clinician-b", ORG_B)
                        .getStatusCode());
        ResponseEntity<byte[]> ownedBytes = content(
                "/api/v1/documents/associations/" + fileA + "/content", "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, ownedBytes.getStatusCode());
        assertEquals(new String(ORIGINAL, StandardCharsets.UTF_8),
                new String(ownedBytes.getBody(), StandardCharsets.UTF_8));

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
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.POST, "/api/v1/delivery/events/" + eventA + "/sent", null,
                        "worker-b", ORG_B, Map.of("If-Match", "\"1\"")).getStatusCode());
        assertEquals(HttpStatus.OK,
                request(HttpMethod.POST, "/api/v1/delivery/events/" + eventA + "/sent", null,
                        "worker-a", ORG_A, Map.of("If-Match", "\"1\"")).getStatusCode());

        // Fail closed on every existing surface, on reads and on writes.
        assertEquals(HttpStatus.UNAUTHORIZED,
                request(HttpMethod.GET, "/api/v1/facilities/MAIN", null, null, ORG_A).getStatusCode());
        assertEquals(422, request(HttpMethod.GET, "/api/v1/patients?name=shared",
                null, "reception-a", null).getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED, request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"NO-CONTEXT\",\"name\":\"No context\"}", null, ORG_A).getStatusCode());
        assertEquals(422, request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"NO-ORG\",\"name\":\"No org\"}", "admin-a", null).getStatusCode().value());
        assertEquals(HttpStatus.FORBIDDEN,
                request(HttpMethod.GET, "/api/v1/facilities/MAIN", null, "admin-a", ORG_B).getStatusCode());
        // A direct service call with no thread context cannot open a tenant transaction.
        TenantContext.clear();
        assertThrows(IllegalStateException.class, () -> facilityService.get("MAIN"));
    }

    /**
     * ARC-02 / G0: tenant exports are tenant scoped. Both tenants use the same
     * local object alias; forced RLS confines each job row to its owner and the
     * private object store resolves that alias to the owner's promoted manifest
     * only. The HTTP download entry point is separately noted as defective in
     * the hand-off notes and is not claimed as proven here.
     */
    @Test
    void exportsIsolateIdenticalLocalObjectIdentifiers() throws Exception {
        String sharedObject = SHARED_EXPORT_OBJECT;
        String jobA = "aaa11111-1111-4111-8111-111111111111";
        String jobB = "bbb22222-2222-4222-8222-222222222222";
        String manifestA = "{\"schemaVersion\":\"1.0\",\"organizationId\":\"" + ORG_A
                + "\",\"marker\":\"MANIFEST-A\",\"sha256\":\"" + DIGEST + "\"}";
        String manifestB = "{\"schemaVersion\":\"1.0\",\"organizationId\":\"" + ORG_B
                + "\",\"marker\":\"MANIFEST-B\",\"sha256\":\"" + DIGEST + "\"}";
        String checksumA = promoteManifest(ORG_A, sharedObject, manifestA.getBytes(StandardCharsets.UTF_8));
        String checksumB = promoteManifest(ORG_B, sharedObject, manifestB.getBytes(StandardCharsets.UTF_8));
        seedExportJob(ORG_A, jobA, sharedObject, checksumA);
        seedExportJob(ORG_B, jobB, sharedObject, checksumB);

        // Forced RLS with WITH CHECK on export_job confines each tenant to its
        // own row even though object_id is byte-identical across the two tenants.
        assertEquals("1", scalar(ORG_A, "admin-a",
                "SELECT count(*) FROM public.export_job WHERE id = '" + jobA + "'"));
        assertEquals("1", scalar(ORG_B, "admin-b",
                "SELECT count(*) FROM public.export_job WHERE id = '" + jobB + "'"));
        assertEquals("0", scalar(ORG_A, "admin-a",
                "SELECT count(*) FROM public.export_job WHERE id = '" + jobB + "'"));
        assertEquals("0", scalar(ORG_B, "admin-b",
                "SELECT count(*) FROM public.export_job WHERE id = '" + jobA + "'"));
        assertEquals("1", scalar(ORG_A, "admin-a",
                "SELECT count(*) FROM public.export_job WHERE object_id = '" + sharedObject + "'"));
        assertEquals("1", scalar(ORG_B, "admin-b",
                "SELECT count(*) FROM public.export_job WHERE object_id = '" + sharedObject + "'"));

        // The identical local alias resolves to each owner's promoted bytes only;
        // a checksum minted by the peer tenant cannot read this tenant's object.
        ObjectKey keyA = new ObjectKey(ORG_A, sharedObject, 1);
        ObjectKey keyB = new ObjectKey(ORG_B, sharedObject, 1);
        assertEquals(manifestA, new String(objectStore.getVerified(keyA, checksumA).orElseThrow(),
                StandardCharsets.UTF_8));
        assertEquals(manifestB, new String(objectStore.getVerified(keyB, checksumB).orElseThrow(),
                StandardCharsets.UTF_8));
        assertTrue(objectStore.getVerified(keyA, checksumB).isEmpty());
        assertTrue(objectStore.getVerified(keyB, checksumA).isEmpty());

        // Missing context fails closed at the HTTP boundary before any service work.
        assertEquals(HttpStatus.UNAUTHORIZED, request(HttpMethod.GET, "/api/v1/tenant/exports/" + jobA,
                null, null, ORG_A).getStatusCode());
        assertEquals(422, request(HttpMethod.GET, "/api/v1/tenant/exports/" + jobA,
                null, "admin-a", null).getStatusCode().value());
    }

    /**
     * ARC-02 / IAM-02 / G0: a deactivated member loses access on the next
     * protected request, while the peer tenant's membership is unaffected.
     * Revocation of outstanding invitations and protected releases is a
     * separate G3 access path and is not claimed by this test.
     */
    @Test
    void revocationAppliesOnTheNextRequest() {
        assertEquals(HttpStatus.CREATED, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"revoke-a\",\"displayName\":\"Revocable A\",\"role\":\"tenant_admin\"}",
                "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/api/v1/facilities/MAIN",
                null, "revoke-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/staff/revoke-a/deactivate",
                "{}", "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET, "/api/v1/facilities/MAIN",
                null, "revoke-a", ORG_A).getStatusCode());
        assertEquals("deactivated", field(request(HttpMethod.GET, "/api/v1/staff/revoke-a",
                null, "admin-a", ORG_A).getBody(), "status"));
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/api/v1/facilities/MAIN",
                null, "admin-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.CREATED, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"revoke-b\",\"displayName\":\"Revocable B\",\"role\":\"tenant_admin\"}",
                "admin-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/staff/revoke-b/deactivate",
                "{}", "admin-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET, "/api/v1/facilities/MAIN",
                null, "revoke-b", ORG_B).getStatusCode());
        // The other tenant members and the actor who performed the revocation
        // keep their access — revocation is per member, not a global switch.
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/api/v1/facilities/MAIN",
                null, "admin-b", ORG_B).getStatusCode());
    }

    /**
     * CLN-01 / G1: sequential and concurrent bookings for the same protected
     * clinician and room leave exactly one live appointment for the slot.
     */
    @Test
    void competingBookingsKeepOneProtectedSlot() throws Exception {
        Instant sequential = monday(1, 10);
        assertEquals(HttpStatus.CREATED, book("DR-A", "ROOM-1", sequential, PATIENT_A,
                "reception-a", ORG_A).getStatusCode());
        ResponseEntity<String> conflict = book("DR-A", "ROOM-1", sequential, PATIENT_A,
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals("SLOT_CONFLICT", field(conflict.getBody(), "code"));

        Instant concurrent = monday(2, 11);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return book("DR-A", "ROOM-1", concurrent, PATIENT_A, "reception-a", ORG_A);
                }));
            }
            int created = 0;
            int conflicted = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(60, TimeUnit.SECONDS);
                HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
                if (status == HttpStatus.CREATED) {
                    created++;
                } else if (status == HttpStatus.CONFLICT) {
                    conflicted++;
                } else {
                    fail("unexpected booking status " + status + ": " + response.getBody());
                }
            }
            assertEquals(1, created, "only one competing booking may win the protected slot");
            assertEquals(1, conflicted, "the competing booking must be refused with 409");
        } finally {
            executor.shutdownNow();
        }
        assertEquals("1", scalar(ORG_A, "reception-a", "SELECT count(*) FROM public.appointment "
                + "WHERE starts_at = '" + concurrent + "' "
                + "AND state IN ('held','booked','checked_in','in_progress')"));
    }

    /**
     * CLN-05 / G1: a stale draft version cannot overwrite a signed note, and
     * the signed revision plus its author remain in the append-only history.
     */
    @Test
    void staleDraftCannotOverwriteSignedNote() {
        String body = "{\"patientId\":\"" + PATIENT_A + "\",\"title\":\"Visit note\","
                + "\"content\":\"Chief concern: chest pain\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"clinician-a\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/clinical-notes",
                body, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals("draft", field(created.getBody(), "state"));
        assertEquals("1", field(created.getBody(), "version"));

        ResponseEntity<String> signed = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"I attest I reviewed this note\"}", "clinician-a", ORG_A,
                Map.of("If-Match", "\"1\""));
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("clinician-a", field(signed.getBody(), "signerSubject"));
        String signedChecksum = field(signed.getBody(), "contentChecksum");

        String lateEdit = "{\"title\":\"Late edit\",\"content\":\"Late content\"}";
        ResponseEntity<String> stale = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                lateEdit, "clinician-a", ORG_A, Map.of("If-Match", "\"1\""));
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));

        ResponseEntity<String> immutable = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                lateEdit, "clinician-a", ORG_A, Map.of("If-Match", "\"2\""));
        assertEquals(HttpStatus.CONFLICT, immutable.getStatusCode());
        assertEquals("IMMUTABLE_REVISION", field(immutable.getBody(), "code"));

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(view.getBody().contains("\"draft\""));
        assertTrue(view.getBody().contains("\"signed\""));
        assertTrue(view.getBody().contains("Chief concern: chest pain"));
        assertFalse(view.getBody().contains("Late content"));
        // The signed checksum must be the digest of the original draft content
        // and must be the value preserved in the append-only revision history,
        // not an echo of the sign response.
        assertFalse(signedChecksum.isBlank());
        assertEquals(sha256("Chief concern: chest pain".getBytes(StandardCharsets.UTF_8)), signedChecksum);
        assertEquals(signedChecksum, field(view.getBody(), "contentChecksum"));
    }

    /**
     * IAM-04 / G1: consolidation is all or nothing. An unresolved dependency
     * blocks the command without any partial write; once resolved the whole
     * unit commits (chart association, consolidation row, effective id); a
     * repeat is refused and a peer tenant cannot reach the review.
     */
    @Test
    void consolidationIsAllOrNothing() throws Exception {
        String survivor = register("Amina Khan", "PLAT-1", "CLINIC-A", "reception-a", ORG_A);
        String duplicate = register("Amina Khan", "PLAT-2", "CLINIC-A", "reception-a", ORG_A);
        String reviewBody = "{\"survivorId\":\"" + survivor + "\",\"duplicateId\":\"" + duplicate + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", ORG_A);
        assertEquals(HttpStatus.CREATED, opened.getStatusCode(), opened.getBody());
        String reviewId = field(opened.getBody(), "id");
        String command = "/api/v1/duplicate-reviews/" + reviewId + "/consolidate";
        String approval = "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\"PLAT-1@CLINIC-A\","
                + "\"PLAT-2@CLINIC-A\"]}";

        execute(ORG_A, "reviewer-a", "INSERT INTO public.consolidation_dependency "
                + "(organization_id, patient_id, kind, reference, ambiguous, resolved) VALUES ('"
                + ORG_A + "', '" + duplicate + "', 'ORDER', 'order-platform', true, false)");
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, command, approval,
                "approver-a", ORG_A, Map.of("If-Match", "\"1\"")).getStatusCode());
        assertEquals("0", scalar(ORG_A, "approver-a", "SELECT count(*) FROM public.chart_association "
                + "WHERE alias_id = '" + duplicate + "'"));
        assertEquals("0", scalar(ORG_A, "approver-a", "SELECT count(*) FROM public.consolidation "
                + "WHERE review_id = '" + reviewId + "'"));

        // Dependencies are append-only: resolving one appends a second row that
        // the review fold treats as the current, resolved decision.
        execute(ORG_A, "approver-a", "INSERT INTO public.consolidation_dependency "
                + "(organization_id, patient_id, kind, reference, ambiguous, resolved) VALUES ('"
                + ORG_A + "', '" + duplicate + "', 'ORDER', 'order-platform', true, true)");
        ResponseEntity<String> done = request(HttpMethod.POST, command, approval,
                "approver-a", ORG_A, Map.of("If-Match", "\"1\""));
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals("CONSOLIDATED", field(done.getBody(), "status"));
        assertEquals("1", scalar(ORG_A, "approver-a", "SELECT count(*) FROM public.chart_association "
                + "WHERE alias_id = '" + duplicate + "'"));
        assertEquals("1", scalar(ORG_A, "approver-a", "SELECT count(*) FROM public.consolidation "
                + "WHERE review_id = '" + reviewId + "'"));
        assertEquals(survivor, scalar(ORG_A, "approver-a",
                "SELECT public.effective_patient_id('" + duplicate + "')::text"));
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, command, approval,
                "approver-a", ORG_A, Map.of("If-Match", "\"1\"")).getStatusCode());

        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + survivor + "/" + duplicate, null,
                "reviewer-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST, command, approval,
                "approver-b", ORG_B, Map.of("If-Match", "\"1\"")).getStatusCode());
    }

    /** Register a synthetic duplicate patient through the real patient entry point. */
    private String register(String name, String mrn, String issuer, String actor, String organization) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer
                + "\",\"dob\":\"1988-03-03\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body, actor, organization);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    /** Create a document association carrying the shared local file name. */
    private String linkFile(String chart, String subject, String organization) {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chart + "\",\"fileName\":\""
                + SHARED_FILE + "\",\"mediaType\":\"application/pdf\",\"contentBase64\":\"" + ENCODED
                + "\",\"rotationDegrees\":0}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/documents/associations",
                body, subject, organization);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    /** Publish a worker outbox event carrying the shared idempotency key. */
    private String publishEvent(String subject, String organization) {
        String body = "{\"aggregateId\":\"" + UUID.randomUUID() + "\",\"aggregateVersion\":1,"
                + "\"sender\":\"lab-partner\",\"eventType\":\"observation.result\","
                + "\"namespace\":\"partner-lab\",\"payloadDigest\":\"" + DIGEST + "\","
                + "\"idempotencyKey\":\"" + SHARED_EVENT_KEY + "\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/delivery/events",
                body, subject, organization);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "eventId");
    }

    /** Promote a synthetic manifest object in the private object store. */
    private String promoteManifest(String organization, String objectId, byte[] content) {
        ObjectKey key = new ObjectKey(organization, objectId, 1);
        StoredObject quarantined = objectStore.putInQuarantine(key, content);
        objectStore.promote(key, quarantined.checksum());
        return quarantined.checksum();
    }

    /** Seed a durable export job row for one tenant with a future expiry. */
    private static void seedExportJob(String organization, String jobId, String objectId, String checksum)
            throws Exception {
        execute(organization, "admin-" + suffixOf(organization), "INSERT INTO public.export_job "
                + "(organization_id, id, status, object_id, object_version, checksum, created_at, expires_at) "
                + "VALUES ('" + organization + "', '" + jobId + "', 'COMPLETE', '" + objectId
                + "', 1, '" + checksum + "', now(), now() + interval '1 day')");
    }
}
