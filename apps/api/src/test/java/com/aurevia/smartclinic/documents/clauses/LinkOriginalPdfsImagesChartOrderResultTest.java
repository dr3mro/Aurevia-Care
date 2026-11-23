package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * CLN-07 clause 1 HTTP acceptance: authorized linking of original PDFs and
 * images to a chart, an order or a result (synthetic data only).
 *
 * <p>The proof enters through {@code POST /api/v1/documents/associations} and
 * reads back through {@code GET /api/v1/documents/associations/{id}/content},
 * so the controller, service, policy and repository are exercised together.
 * Negative cases cover the role, the tenant boundary, an unknown chart or order
 * subject, and a revoked membership. Every fixture is synthetic; the database is a fresh,
 * migrated name owned solely by this class.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_link_clause_it")
@ActiveProfiles("synthetic-auth")
class LinkOriginalPdfsImagesChartOrderResultTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String ORDER_ID = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String RESULT_ID = "cccccccc-0000-0000-0000-000000000001";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_link_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final byte[] CHART_PDF =
            "%PDF-1.4 synthetic chart original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ORDER_PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 's', 'y', 'n', 't', 'h', 'e', 't', 'i', 'c'
    };
    private static final byte[] RESULT_PDF =
            "%PDF-1.4 synthetic result original".getBytes(StandardCharsets.UTF_8);

    RestClient rest;

    /** Bind the shared RestClient to the randomly allocated test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create a fresh database, migrate it and seed synthetic members and
     * canonical order/result subjects. The unique name isolates this class from
     * every other integration test in the swarm.
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_doc_link_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_doc_link_clause_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, ORG_A, "clinician-a", "clinician", "active");
            seedMember(statement, ORG_A, "reception-a", "reception", "active");
            seedMember(statement, ORG_A, "finance-a", "finance", "active");
            seedMember(statement, ORG_A, "revoked-a", "clinician", "revoked");
            seedMember(statement, ORG_B, "clinician-b", "clinician", "active");
        }
        seedCanonicalSubjects();
    }

    /** Drop the class-private database so a later run starts from a clean slate. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_doc_link_clause_it WITH (FORCE)");
        }
    }

    /**
     * Insert one synthetic member with an explicit membership state. A revoked
     * membership still carries its role grant so a denial isolates membership,
     * not authorization wiring.
     */
    private static void seedMember(Statement statement, String org, String subject,
                                   String role, String membershipStatus) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + membershipStatus
                + "' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /**
     * Seed the canonical tables that own order and result identifiers. Replica
     * mode bypasses inserts' actor triggers and cross-module foreign keys for
     * this one seed, then is restored so the application runs under normal
     * forced RLS and trigger semantics.
     */
    private static void seedCanonicalSubjects() throws Exception {
        try (Connection root = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                statement.execute("INSERT INTO public.patient_record "
                        + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                        + "SELECT '" + ORG_A + "', '" + PATIENT_ID + "', 1, 'MRN-SYN-LINK', 'CLINIC', "
                        + "'Synthetic Subject', 'synthetic subject', '1990-05-20', u.id "
                        + "FROM public.user_identity u WHERE u.organization_id = '" + ORG_A
                        + "' AND u.subject = 'clinician-a'");
                statement.execute("INSERT INTO public.exchange_order "
                        + "(organization_id, id, version, patient_id, encounter_ref, order_code, priority, "
                        + "clinical_question, destination_org, source_actor, responsible_clinician, work_queue, "
                        + "authorization_id, snapshot_id, disclosure_id, work_item_id, created_by) "
                        + "SELECT '" + ORG_A + "', '" + ORDER_ID + "', 1, '" + PATIENT_ID + "', 'ENC-SYN', "
                        + "'CBC', 'routine', 'synthetic question', '" + ORG_B + "', 'clinician-a', "
                        + "'dr-a', 'lab-queue', '" + randomUuid() + "', '" + randomUuid() + "', '"
                        + randomUuid() + "', '" + randomUuid() + "', u.id "
                        + "FROM public.user_identity u WHERE u.organization_id = '" + ORG_A
                        + "' AND u.subject = 'clinician-a'");
                statement.execute("INSERT INTO public.xch04_report_revision "
                        + "(organization_id, id, version, order_ref, patient_id, source_code, accession, "
                        + "destination_code, kind, owner_subject, coverage_queue, effective_at, checksum, created_by) "
                        + "SELECT '" + ORG_A + "', '" + RESULT_ID + "', 1, 'ORD-SYN-LINK', '" + PATIENT_ID
                        + "', 'LAB', 'ACC-SYN-LINK', 'CLINIC', 'final', 'clinician-a', 'lab-queue', "
                        + "now(), 'synthetic-checksum', u.id "
                        + "FROM public.user_identity u WHERE u.organization_id = '" + ORG_A
                        + "' AND u.subject = 'clinician-a'");
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
    }

    /** Create one chart subject through the normal HTTP registration entry point. */
    private String registerChart() {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-"
                + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = rest.post().uri("/api/v1/patients")
                .header("X-Synthetic-Subject", "reception-a")
                .header("X-Active-Organization", ORG_A)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /** Submit one link command with explicit actor and active organization. */
    private ResponseEntity<String> postLink(String subjectType, String subjectId, String fileName,
                                            String mediaType, byte[] original, Integer rotation,
                                            String subject, String organization) {
        String body = "{\"subjectType\":\"" + subjectType + "\",\"subjectId\":\"" + subjectId
                + "\",\"fileName\":\"" + fileName + "\",\"mediaType\":\"" + mediaType
                + "\",\"contentBase64\":\"" + Base64.getEncoder().encodeToString(original) + "\""
                + (rotation == null ? "" : ",\"rotationDegrees\":" + rotation) + "}";
        return rest.post().uri("/api/v1/documents/associations")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
    }

    /** Read the retained original through the checksum-verifying HTTP entry point. */
    private ResponseEntity<byte[]> download(String id, String subject, String organization) {
        return rest.get().uri("/api/v1/documents/associations/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .retrieve().onStatus(status -> true, (req, res) -> { })
                .toEntity(byte[].class);
    }

    /** Count associations bound to one synthetic subject, irrespective of tenant. */
    private static int associationCount(String subjectId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, ADMIN, "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM public.document_association WHERE subject_id = CAST(? AS uuid)")) {
            query.setString(1, subjectId);
            var resultSet = query.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /** Compute the expected SHA-256 hex for one retained original. */
    private static String expectedSha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Extract a string field from the compact JSON responses used here. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle) + needle.length();
        while (start < json.length() && (json.charAt(start) == '"' || json.charAt(start) == ' ')) {
            start++;
        }
        int end = json.indexOf('"', start);
        if (end < 0) {
            end = json.indexOf(',', start);
        }
        return json.substring(start, end);
    }

    /** Generate a stable synthetic UUID for a companion row identifier. */
    private static String randomUuid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void linksOriginalPdfToChartAsAuthorizedClinician() throws Exception {
        String chartId = registerChart();
        ResponseEntity<String> response = postLink("chart", chartId, "scan.pdf", "application/pdf",
                CHART_PDF, 90, "clinician-a", ORG_A);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        String body = response.getBody();
        assertEquals("chart", field(body, "subjectType"));
        assertEquals(chartId, field(body, "subjectId"));
        assertEquals("application/pdf", field(body, "mediaType"));
        assertTrue(body.contains("\"rotationDegrees\":90"), body);
        assertEquals(expectedSha256(CHART_PDF), field(body, "sha256"));
        assertEquals(1, associationCount(chartId));

        String id = field(body, "id");
        assertArrayEquals(CHART_PDF, download(id, "clinician-a", ORG_A).getBody());
        ResponseEntity<String> history = rest.get().uri("/api/v1/documents/associations/" + id)
                .header("X-Synthetic-Subject", "clinician-a")
                .header("X-Active-Organization", ORG_A)
                .retrieve().onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, history.getStatusCode());
        assertTrue(history.getBody().contains("\"action\":\"link\""), history.getBody());
    }

    @Test
    void linksOriginalPdfToChartAsAuthorizedReception() throws Exception {
        String chartId = registerChart();
        ResponseEntity<String> response = postLink("chart", chartId, "front-desk.pdf",
                "application/pdf", CHART_PDF, 0, "reception-a", ORG_A);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        assertEquals(chartId, field(response.getBody(), "subjectId"));
        assertEquals(1, associationCount(chartId));
    }

    @Test
    void linksOriginalImageToOrder() throws Exception {
        ResponseEntity<String> response = postLink("order", ORDER_ID, "scan.png", "image/png",
                ORDER_PNG, 0, "clinician-a", ORG_A);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        String body = response.getBody();
        assertEquals("order", field(body, "subjectType"));
        assertEquals(ORDER_ID, field(body, "subjectId"));
        assertEquals("image/png", field(body, "mediaType"));
        assertEquals(expectedSha256(ORDER_PNG), field(body, "sha256"));
        assertEquals(1, associationCount(ORDER_ID));
        assertArrayEquals(ORDER_PNG, download(field(body, "id"), "clinician-a", ORG_A).getBody());
    }

    @Test
    void linksOriginalPdfToResult() throws Exception {
        ResponseEntity<String> response = postLink("result", RESULT_ID, "result.pdf", "application/pdf",
                RESULT_PDF, 0, "clinician-a", ORG_A);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        String body = response.getBody();
        assertEquals("result", field(body, "subjectType"));
        assertEquals(RESULT_ID, field(body, "subjectId"));
        assertEquals(expectedSha256(RESULT_PDF), field(body, "sha256"));
        assertEquals(1, associationCount(RESULT_ID));
        assertArrayEquals(RESULT_PDF, download(field(body, "id"), "clinician-a", ORG_A).getBody());
    }

    @Test
    void refusesLinkByRoleWithoutDocumentLinkPermission() throws Exception {
        String chartId = registerChart();
        ResponseEntity<String> response = postLink("chart", chartId, "scan.pdf", "application/pdf",
                CHART_PDF, 0, "finance-a", ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("FORBIDDEN"), response.getBody());
        assertEquals(0, associationCount(chartId));
    }

    @Test
    void concealsAnotherTenantsSubjectFromLink() throws Exception {
        String chartId = registerChart();
        ResponseEntity<String> response = postLink("chart", chartId, "scan.pdf", "application/pdf",
                CHART_PDF, 0, "clinician-b", ORG_B);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("NOT_FOUND"), response.getBody());
        assertEquals(0, associationCount(chartId));
    }

    @Test
    void refusesLinkToUnknownSubject() throws Exception {
        String unknown = UUID.randomUUID().toString();
        ResponseEntity<String> response = postLink("chart", unknown, "scan.pdf", "application/pdf",
                CHART_PDF, 0, "clinician-a", ORG_A);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("NOT_FOUND"), response.getBody());
        assertEquals(0, associationCount(unknown));
    }

    @Test
    void refusesLinkToUnknownOrderSubject() throws Exception {
        String unknown = UUID.randomUUID().toString();
        ResponseEntity<String> response = postLink("order", unknown, "scan.pdf", "application/pdf",
                CHART_PDF, 0, "clinician-a", ORG_A);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("NOT_FOUND"), response.getBody());
        assertEquals(0, associationCount(unknown));
    }

    @Test
    void refusesLinkForInactiveMembership() throws Exception {
        String chartId = registerChart();
        ResponseEntity<String> response = postLink("chart", chartId, "scan.pdf", "application/pdf",
                CHART_PDF, 0, "revoked-a", ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("FORBIDDEN"), response.getBody());
        assertEquals(0, associationCount(chartId));
    }
}
