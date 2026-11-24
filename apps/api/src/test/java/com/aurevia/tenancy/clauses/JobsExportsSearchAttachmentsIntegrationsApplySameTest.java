package com.aurevia.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.aurevia.api.ApiException;
import com.aurevia.documents.storage.ObjectKey;
import com.aurevia.documents.storage.ObjectStorePort;
import com.aurevia.documents.storage.StoredObject;
import com.aurevia.events.DeliveryStore;
import com.aurevia.events.JdbcDeliveryStore;
import com.aurevia.tenant.TenantContext;
import com.aurevia.tenant.TenantGuardTransactionManager;
import com.aurevia.tenant.export.JdbcExportStore;
import com.aurevia.tenant.export.TenantExportService;

/**
 * ARC-02 clause 5 acceptance evidence: jobs, exports, search, attachments and
 * integrations apply the same tenant-context, role and forced-RLS policy.
 *
 * <p>Two synthetic organizations reuse identical local identifiers (facility
 * code {@code MAIN}, MRN {@code MRN-SHARED}, order code, file name and delivery
 * idempotency key). Every surface must disclose the owner's record, conceal the
 * peer tenant's record with a uniform 404 or an empty result, and fail closed
 * with 401/422 when the subject or active organization is missing. The suite
 * adds no production code and uses synthetic fixtures only.</p>
 *
 * <p>Export job and artifact isolation are proved at the
 * {@link com.aurevia.tenant.export.JdbcExportStore} and
 * {@link com.aurevia.documents.storage.ObjectStorePort} boundaries,
 * and its role and expiry refusals through
 * {@link com.aurevia.tenant.export.TenantExportService} inside the tenant-guarded
 * transaction. The HTTP adapter does not open that transaction, so its audit
 * write and role fold fail before the policy decision (see the clause note and
 * hand-off findings). Missing-context refusal on the export routes is still
 * asserted at the HTTP boundary, before the service is reached.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_arc02_c05_it")
@ActiveProfiles("synthetic-auth")
class JobsExportsSearchAttachmentsIntegrationsApplySameTest {

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
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_arc02_c05_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DeliveryStore deliveryStore;

    @Autowired
    JdbcDeliveryStore jdbcDeliveryStore;

    @Autowired
    TenantExportService tenantExportService;

    @Autowired
    JdbcExportStore exportStore;

    @Autowired
    TenantGuardTransactionManager transactionManager;

    @Autowired
    ObjectStorePort objectStore;

    RestClient rest;

    TransactionTemplate transactions;

    /** Bind a real HTTP client and a tenant-guarded transaction template. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        transactions = new TransactionTemplate(transactionManager);
    }

    /** Recreate the disposable database, apply all migrations and seed two tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_arc02_c05_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_arc02_c05_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
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
            // The same issuer-scoped MRN is seeded once per tenant so search
            // isolation is proved against a genuinely identical local identifier.
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

    /** Drop the disposable database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_arc02_c05_it WITH (FORCE)");
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

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                            String subject, String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> requestWithVersion(HttpMethod method, String uri,
                                                       String subject, String organization, String version) {
        HttpHeaders headers = headers(subject, organization);
        headers.set("If-Match", "\"" + version + "\"");
        return rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve().onStatus(status -> true, (req, response) -> { })
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

    /** Bind tenant context on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String organization, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(organization, subject, "ISOLATION_EVIDENCE", "arc02-c05-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Execute a tenant-scoped SQL fixture with the same GUCs the interceptor binds. */
    private static void execute(String organization, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                statement.setString(1, organization);
                statement.setString(2, subject);
                statement.execute();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
    }

    /** Count rows visible under a bound tenant context; forced RLS does the filtering. */
    private static int countVisible(String organization, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                statement.setString(1, organization);
                statement.setString(2, subject);
                statement.execute();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private String createAuthorization(String destination, String subject, String organization,
                                        String orderCode) {
        String body = "{\"orderCode\":\"" + orderCode + "\",\"destinationOrg\":\"" + destination
                + "\",\"purpose\":\"lab review\",\"maxDurationMinutes\":60,\"validForDays\":2}";
        ResponseEntity<String> response = post("/api/v1/exchange/authorizations", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String createOrder(String patient, String destination, String grant,
                               String subject, String organization, String orderCode) {
        String body = "{\"patientId\":\"" + patient + "\",\"encounterRef\":\"ENC-SHARED\","
                + "\"orderCode\":\"" + orderCode + "\",\"priority\":\"routine\","
                + "\"clinicalQuestion\":\"Synthetic shared-code question\",\"destinationOrg\":\""
                + destination + "\",\"purpose\":\"lab review\",\"authorizationId\":\"" + grant
                + "\",\"responsibleClinician\":\"dr-shared\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":30}";
        ResponseEntity<String> response = post("/api/v1/orders", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String linkFile(String chart, String subject, String organization, String fileName) {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chart
                + "\",\"fileName\":\"" + fileName
                + "\",\"mediaType\":\"application/pdf\",\"contentBase64\":\"" + ENCODED
                + "\",\"rotationDegrees\":0}";
        ResponseEntity<String> response = post("/api/v1/documents/associations", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String publishEvent(String subject, String organization, String key) {
        String body = "{\"aggregateId\":\"" + UUID.randomUUID()
                + "\",\"aggregateVersion\":1,\"sender\":\"lab-partner\","
                + "\"eventType\":\"observation.result\",\"namespace\":\"partner-lab\","
                + "\"payloadDigest\":\"" + DIGEST + "\",\"idempotencyKey\":\"" + key + "\"}";
        ResponseEntity<String> response = post("/api/v1/delivery/events", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "eventId");
    }

    /** Promote a synthetic manifest under a tenant-scoped object alias. */
    private String promoteManifest(String organization, String objectId, byte[] content) {
        ObjectKey key = new ObjectKey(organization, objectId, 1);
        StoredObject quarantined = objectStore.putInQuarantine(key, content);
        objectStore.promote(key, quarantined.checksum());
        return quarantined.checksum();
    }

    /** Seed a durable COMPLETE export job in one tenant and return its opaque id. */
    private static String seedExportJob(String organization, String objectId, String checksum,
                                        String expiresAt) throws Exception {
        String subject = "admin-" + (ORG_A.equals(organization) ? "a" : "b");
        String jobId = UUID.randomUUID().toString();
        execute(organization, subject, "INSERT INTO public.export_job "
                + "(organization_id, id, status, object_id, object_version, checksum, created_at, expires_at) "
                + "VALUES ('" + organization + "', '" + jobId + "', 'COMPLETE', '" + objectId
                + "', 1, '" + checksum + "', now(), " + expiresAt + ")");
        return jobId;
    }

    private static byte[] manifest(String organization, String marker) {
        return ("{\"organization\":\"" + organization + "\",\"marker\":\"" + marker + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Capture the service refusal for a download without asserting the exception type.
     *
     * <p>The service opens its own tenant-guarded transaction. Do not wrap the call in
     * another {@code TransactionTemplate}: the refusal marks that inner transaction
     * rollback-only, so committing an outer one would raise
     * {@link org.springframework.transaction.UnexpectedRollbackException} before the
     * {@link com.aurevia.api.ApiException} status can be asserted. Catch the refusal outside any
     * transaction so the service's transaction rolls back on its own.</p>
     */
    private ApiException exportDownloadRefusal(String organization, String subject, String jobId) {
        return as(organization, subject, () -> {
            try {
                tenantExportService.download(jobId);
                throw new AssertionError("expected the download to be refused");
            } catch (ApiException exception) {
                // The service's own participating transaction marked this transaction
                // rollback-only when it refused. Flag it locally too so the callback
                // returns a clean rollback instead of an UnexpectedRollbackException.
                status.setRollbackOnly();
                return exception;
            }
        });
    }

    /**
     * Capture the service refusal when a role tries to start an export.
     *
     * <p>As with {@link #exportDownloadRefusal(String, String, String)}, the service owns
     * its transaction; catching outside it lets the refusal propagate and the transaction
     * roll back cleanly without a spurious rollback-only commit.</p>
     */
    private ApiException exportStartRefusal(String organization, String subject) {
        return as(organization, subject, () -> {
            try {
                tenantExportService.start();
                throw new AssertionError("expected the export start to be refused");
            } catch (ApiException exception) {
                // The service's own participating transaction marked this transaction
                // rollback-only when it refused. Flag it locally too so the callback
                // returns a clean rollback instead of an UnexpectedRollbackException.
                status.setRollbackOnly();
                return exception;
            }
        });
    }

    @Test
    void allFiveSurfacesApplyTheSameTenantPolicy() throws Exception {
        assertTrue(deliveryStore instanceof JdbcDeliveryStore,
                "the production delivery store must be the PostgreSQL adapter");

        // JOBS: each worker processes its own outbox event, not a peer's.
        String eventA = publishEvent("worker-a", ORG_A, SHARED_EVENT_KEY + "-policy");
        String eventB = publishEvent("worker-b", ORG_B, SHARED_EVENT_KEY + "-policy");
        assertNotEquals(eventA, eventB);
        assertEquals(eventA, as(ORG_A, "worker-a", () -> jdbcDeliveryStore
                .outboxEvent(ORG_A, eventA).orElseThrow().eventId()));
        assertEquals(eventB, as(ORG_B, "worker-b", () -> jdbcDeliveryStore
                .outboxEvent(ORG_B, eventB).orElseThrow().eventId()));
        assertEquals(HttpStatus.OK, requestWithVersion(HttpMethod.POST,
                "/api/v1/delivery/events/" + eventA + "/sent", "worker-a", ORG_A, "1").getStatusCode());

        // EXPORTS: the same local object alias resolves to each owner's manifest,
        // and the job row is readable only inside its owner's guarded transaction.
        byte[] bytesA = manifest(ORG_A, "MANIFEST-A");
        byte[] bytesB = manifest(ORG_B, "MANIFEST-B");
        String checksumA = promoteManifest(ORG_A, "policy-export-object", bytesA);
        String checksumB = promoteManifest(ORG_B, "policy-export-object", bytesB);
        assertNotEquals(checksumA, checksumB);
        String jobA = seedExportJob(ORG_A, "policy-export-object", checksumA, "now() + interval '1 day'");
        String jobB = seedExportJob(ORG_B, "policy-export-object", checksumB, "now() + interval '1 day'");
        assertTrue(as(ORG_A, "admin-a",
                () -> transactions.execute(status -> exportStore.find(jobA))).isPresent());
        assertTrue(as(ORG_B, "admin-b",
                () -> transactions.execute(status -> exportStore.find(jobB))).isPresent());
        byte[] ownManifest = objectStore.getVerified(
                new ObjectKey(ORG_A, "policy-export-object", 1), checksumA).orElseThrow();
        assertTrue(new String(ownManifest, StandardCharsets.UTF_8).contains(ORG_A));
        assertTrue(new String(ownManifest, StandardCharsets.UTF_8).contains("MANIFEST-A"));
        assertTrue(new String(objectStore.getVerified(
                new ObjectKey(ORG_B, "policy-export-object", 1), checksumB).orElseThrow(),
                StandardCharsets.UTF_8).contains("MANIFEST-B"));

        // SEARCH: each tenant finds its own patient with the shared MRN label.
        ResponseEntity<String> searchA = get("/api/v1/patients?name=shared", "reception-a", ORG_A);
        ResponseEntity<String> searchB = get("/api/v1/patients?name=shared", "reception-b", ORG_B);
        assertEquals(HttpStatus.OK, searchA.getStatusCode());
        assertEquals(HttpStatus.OK, searchB.getStatusCode());
        assertTrue(searchA.getBody().contains(PATIENT_A));
        assertTrue(searchA.getBody().contains("Shared Alpha"));
        assertTrue(searchB.getBody().contains(PATIENT_B));
        assertTrue(searchB.getBody().contains("Shared Beta"));

        // ATTACHMENTS: the identically named document is readable by its owner.
        String fileA = linkFile(PATIENT_A, "clinician-a", ORG_A, SHARED_FILE_NAME + "-policy");
        String fileB = linkFile(PATIENT_B, "clinician-b", ORG_B, SHARED_FILE_NAME + "-policy");
        assertNotEquals(fileA, fileB);
        assertEquals(SHARED_FILE_NAME + "-policy",
                field(get("/api/v1/documents/associations/" + fileA, "clinician-a", ORG_A).getBody(), "fileName"));
        assertEquals(HttpStatus.OK, content(
                "/api/v1/documents/associations/" + fileA + "/content", "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, content(
                "/api/v1/documents/associations/" + fileB + "/content", "clinician-b", ORG_B).getStatusCode());

        // INTEGRATIONS: each tenant reads its own order under the shared code.
        String code = SHARED_ORDER_CODE + "-policy";
        String grantA = createAuthorization(ORG_B, "admin-a", ORG_A, code);
        String grantB = createAuthorization(ORG_A, "admin-b", ORG_B, code);
        String orderA = createOrder(PATIENT_A, ORG_B, grantA, "clinician-a", ORG_A, code);
        String orderB = createOrder(PATIENT_B, ORG_A, grantB, "clinician-b", ORG_B, code);
        assertNotEquals(orderA, orderB);
        assertTrue(get("/api/v1/orders/" + orderA, "clinician-a", ORG_A).getBody().contains(code));
        assertTrue(get("/api/v1/orders/" + orderB, "clinician-b", ORG_B).getBody().contains(code));
    }

    @Test
    void peerTenantCannotUseJobsExportsSearchAttachmentsOrIntegrations() throws Exception {
        // JOBS: a peer worker sees no outbox row and cannot advance the event.
        String eventA = publishEvent("worker-a", ORG_A, SHARED_EVENT_KEY + "-peer");
        assertTrue(as(ORG_B, "worker-b", () -> jdbcDeliveryStore.outboxEvent(ORG_B, eventA)).isEmpty());
        assertEquals(HttpStatus.NOT_FOUND, requestWithVersion(HttpMethod.POST,
                "/api/v1/delivery/events/" + eventA + "/sent", "worker-b", ORG_B, "1").getStatusCode());

        // EXPORTS: forced RLS makes the peer's job row and object invisible.
        String checksumA = promoteManifest(ORG_A, "peer-export-object", manifest(ORG_A, "MANIFEST-A"));
        String checksumB = promoteManifest(ORG_B, "peer-export-object", manifest(ORG_B, "MANIFEST-B"));
        String jobA = seedExportJob(ORG_A, "peer-export-object", checksumA, "now() + interval '1 day'");
        assertEquals(404, exportDownloadRefusal(ORG_B, "admin-b", jobA).status());
        assertTrue(as(ORG_B, "admin-b",
                () -> transactions.execute(status -> exportStore.find(jobA))).isEmpty());
        assertEquals(0, countVisible(ORG_B, "admin-b",
                "SELECT count(*) FROM public.export_job WHERE id = '" + jobA + "'"));
        assertTrue(objectStore.getVerified(
                new ObjectKey(ORG_A, "peer-export-object", 1), checksumA).isPresent());
        assertTrue(objectStore.getVerified(
                new ObjectKey(ORG_B, "peer-export-object", 1), checksumB).isPresent());
        assertTrue(objectStore.getVerified(
                new ObjectKey(ORG_B, "peer-export-object", 1), checksumA).isEmpty());
        assertTrue(objectStore.getVerified(
                new ObjectKey(ORG_A, "peer-export-object", 1), checksumB).isEmpty());

        // SEARCH: the peer's patient is absent from the list and a direct read is 404.
        ResponseEntity<String> searchB = get("/api/v1/patients?name=shared", "reception-b", ORG_B);
        assertFalse(searchB.getBody().contains(PATIENT_A));
        assertFalse(searchB.getBody().contains("Shared Alpha"));
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/patients/" + PATIENT_A, "reception-b", ORG_B).getStatusCode());

        // ATTACHMENTS: neither metadata nor bytes of the peer's file are disclosed.
        String fileA = linkFile(PATIENT_A, "clinician-a", ORG_A, SHARED_FILE_NAME + "-peer");
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/associations/" + fileA, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, content(
                "/api/v1/documents/associations/" + fileA + "/content", "clinician-b", ORG_B).getStatusCode());

        // INTEGRATIONS: the peer's order is concealed in reads and lists.
        String code = SHARED_ORDER_CODE + "-peer";
        String grantA = createAuthorization(ORG_B, "admin-a", ORG_A, code);
        String orderA = createOrder(PATIENT_A, ORG_B, grantA, "clinician-a", ORG_A, code);
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/orders/" + orderA, "clinician-b", ORG_B).getStatusCode());
        assertFalse(get("/api/v1/orders", "clinician-b", ORG_B).getBody().contains(orderA));

        // A valid subject asserting the other tenant's organization has no membership.
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/v1/patients?name=shared", "reception-a", ORG_B).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("/api/v1/orders", "clinician-a", ORG_B).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/v1/documents/associations/" + fileA, "clinician-a", ORG_B).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/v1/delivery/dead-letters", "worker-a", ORG_B).getStatusCode());
    }

    @Test
    void missingSubjectOrOrganizationIsRefusedOnEverySurface() {
        // No authenticated subject: refuse before any tenant-scoped work.
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/delivery/dead-letters", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/tenant/exports/" + UNKNOWN, null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                post("/api/v1/tenant/exports", "{}", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/patients?name=shared", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/documents/associations/" + UNKNOWN, null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/patient/files/" + UNKNOWN + "/download", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/orders", null, ORG_A).getStatusCode());

        // No explicit active organization: fail closed instead of a first membership.
        assertEquals(422, get("/api/v1/delivery/dead-letters", "worker-a", null).getStatusCode().value());
        assertEquals(422,
                get("/api/v1/tenant/exports/" + UNKNOWN, "admin-a", null).getStatusCode().value());
        assertEquals(422, post("/api/v1/tenant/exports", "{}", "admin-a", null).getStatusCode().value());
        assertEquals(422,
                get("/api/v1/patients?name=shared", "reception-a", null).getStatusCode().value());
        assertEquals(422,
                get("/api/v1/documents/associations/" + UNKNOWN, "clinician-a", null).getStatusCode().value());
        assertEquals(422, get("/api/v1/patient/files/" + UNKNOWN + "/download",
                "clinician-a", null).getStatusCode().value());
        assertEquals(422, get("/api/v1/orders", "clinician-a", null).getStatusCode().value());
    }

    @Test
    void wrongRoleCannotStartAnExport() {
        // ExportPermissionGuard refuses every non-admin role; only an active tenant_admin is admitted.
        assertEquals(403, exportStartRefusal(ORG_A, "reception-a").status());
        assertEquals(403, exportStartRefusal(ORG_A, "clinician-a").status());
        assertEquals(403, exportStartRefusal(ORG_A, "worker-a").status());
        assertEquals(403, exportStartRefusal(ORG_A, "auditor-a").status());
    }

    @Test
    void staleAndExpiredStatesAreRefused() throws Exception {
        // Stale If-Match on a delivery state transition is refused with 412.
        String event = publishEvent("worker-a", ORG_A, SHARED_EVENT_KEY + "-stale");
        assertEquals(HttpStatus.OK, requestWithVersion(HttpMethod.POST,
                "/api/v1/delivery/events/" + event + "/sent", "worker-a", ORG_A, "1").getStatusCode());
        assertEquals(412, requestWithVersion(HttpMethod.POST,
                "/api/v1/delivery/events/" + event + "/sent", "worker-a", ORG_A, "1")
                .getStatusCode().value());

        // An explicitly expired export job refuses its manifest download with 410.
        String checksum = promoteManifest(ORG_A, "expired-export-object", manifest(ORG_A, "EXPIRED"));
        String jobId = seedExportJob(ORG_A, "expired-export-object", checksum, "now() + interval '1 day'");
        as(ORG_A, "admin-a", () -> {
            transactions.executeWithoutResult(status -> tenantExportService.expire(jobId));
            return Boolean.TRUE;
        });
        assertEquals(410, exportDownloadRefusal(ORG_A, "admin-a", jobId).status());
    }
}
