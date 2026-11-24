package com.aurevia.it.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** S-G1-07 acceptance: original associations and localized renders on real PostgreSQL (CLN-07). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_document_it")
@ActiveProfiles("synthetic-auth")
class DocumentIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String UNKNOWN = "99999999-9999-9999-9999-999999999999";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_document_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_document_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_document_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "reception-b", "reception");
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_document_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject, String role) throws Exception {
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

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String uri, String body, String subject, String org) {
        return request(HttpMethod.POST, uri, body, subject, org, null);
    }

    private ResponseEntity<String> get(String uri, String subject, String org) {
        return request(HttpMethod.GET, uri, null, subject, org, null);
    }

    private ResponseEntity<byte[]> content(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/rendered/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(byte[].class);
    }

    private String registerChart(String org, String reception) {
        // Each registration needs a fresh issuer-scoped MRN: V7 makes (org, issuer,
        // mrn, version) unique and the database is shared across the test methods.
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-" + org.substring(0, 2)
                + "-" + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = post("/api/v1/patients", body, reception, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String link(String org, String clinician, String subjectType, String subjectId,
                        String mediaType, String encoded) {
        String body = "{\"subjectType\":\"" + subjectType + "\",\"subjectId\":\"" + subjectId
                + "\",\"fileName\":\"scan.bin\",\"mediaType\":\"" + mediaType
                + "\",\"contentBase64\":\"" + encoded + "\",\"rotationDegrees\":90}";
        ResponseEntity<String> response = post("/api/v1/documents/associations", body, clinician, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String render(String org, String clinician, String subjectId, String locale, String title) {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + subjectId
                + "\",\"locale\":\"" + locale + "\",\"title\":\"" + title
                + "\",\"lines\":[\"Synthetic line\"],\"sourceSystem\":\"SYNTHETIC-LAB\","
                + "\"sourceId\":\"REQ-1\",\"effectiveAt\":\"2026-11-19T10:00:00Z\","
                + "\"rotationDegrees\":0,\"sourceAssociationId\":null}";
        ResponseEntity<String> response = post("/api/v1/documents/rendered", body, clinician, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
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

    @Test
    void storesOriginalBytesWithDatabaseChecksumAndHistory() throws Exception {
        String chart = registerChart(ORG_A, "reception-a");
        String id = link(ORG_A, "clinician-a", "chart", chart, "application/pdf", ENCODED);
        ResponseEntity<String> created = get("/api/v1/documents/associations/" + id, "clinician-a", ORG_A);
        String body = created.getBody();
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(ORIGINAL));
        assertEquals(expected, field(body, "sha256"));
        assertEquals(String.valueOf(ORIGINAL.length), field(body, "byteSize"));
        assertEquals("90", field(body, "rotationDegrees"));
        assertEquals("active", field(body, "status"));
        assertTrue(body.contains("\"history\""));
        assertTrue(body.contains("\"link\""));
        assertFalse(body.contains("contentBase64"), "original bytes must not leak into the view");
    }

    @Test
    void twoTenantsWithTheSameLocalIdentifiersStayIsolated() {
        String chartA = registerChart(ORG_A, "reception-a");
        String chartB = registerChart(ORG_B, "reception-b");
        String idA = link(ORG_A, "clinician-a", "chart", chartA, "application/pdf", ENCODED);
        String idB = link(ORG_B, "clinician-b", "chart", chartB, "application/pdf", ENCODED);
        assertNotEquals(idA, idB);
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/associations/" + idA, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/associations/" + idB, "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK,
                get("/api/v1/documents/associations/" + idA, "clinician-a", ORG_A).getStatusCode());
    }

    @Test
    void deniesWrongRolesMissingIdentityAndInvalidInputs() {
        String chart = registerChart(ORG_A, "reception-a");
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chart
                + "\",\"fileName\":\"scan.bin\",\"mediaType\":\"application/pdf\","
                + "\"contentBase64\":\"" + ENCODED + "\",\"rotationDegrees\":0}";
        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/v1/documents/associations", body, null, ORG_A).getStatusCode());
        String validRender = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chart
                + "\",\"locale\":\"en\",\"title\":\"Summary\",\"lines\":[\"line\"],"
                + "\"sourceSystem\":\"SYNTHETIC-LAB\",\"sourceId\":\"REQ-1\","
                + "\"effectiveAt\":\"2026-11-19T10:00:00Z\",\"rotationDegrees\":0,"
                + "\"sourceAssociationId\":null}";
        assertEquals(HttpStatus.FORBIDDEN,
                post("/api/v1/documents/rendered", validRender, "reception-a", ORG_A).getStatusCode());
        assertEquals(422, post("/api/v1/documents/rendered", "{}", "clinician-a", ORG_A)
                .getStatusCode().value());
        String badMedia = body.replace("application/pdf", "image/gif");
        assertEquals(422, post("/api/v1/documents/associations", badMedia, "clinician-a", ORG_A)
                .getStatusCode().value());
        String missingChart = body.replace(chart, UNKNOWN);
        assertEquals(HttpStatus.NOT_FOUND,
                post("/api/v1/documents/associations", missingChart, "clinician-a", ORG_A).getStatusCode());
    }

    @Test
    void voidRequiresIfMatchAndReasonAndPreservesHistory() {
        String chart = registerChart(ORG_A, "reception-a");
        String id = link(ORG_A, "clinician-a", "chart", chart, "application/pdf", ENCODED);
        String uri = "/api/v1/documents/associations/" + id + "/void";
        assertEquals(HttpStatus.PRECONDITION_REQUIRED,
                post(uri, "{\"reason\":\"wrong chart\"}", "clinician-a", ORG_A).getStatusCode());
        assertEquals(422, request(HttpMethod.POST, uri, "{\"reason\":\"  \"}",
                "clinician-a", ORG_A, "1").getStatusCode().value());
        assertEquals(HttpStatus.PRECONDITION_FAILED,
                request(HttpMethod.POST, uri, "{\"reason\":\"wrong chart\"}",
                        "clinician-a", ORG_A, "9").getStatusCode());
        ResponseEntity<String> voided = request(HttpMethod.POST, uri,
                "{\"reason\":\"wrong chart\"}", "clinician-a", ORG_A, "1");
        assertEquals(HttpStatus.OK, voided.getStatusCode(), voided.getBody());
        assertEquals("voided", field(voided.getBody(), "status"));
        assertEquals("wrong chart", field(voided.getBody(), "voidReason"));
        assertEquals(HttpStatus.CONFLICT,
                request(HttpMethod.POST, uri, "{\"reason\":\"again\"}",
                        "clinician-a", ORG_A, "2").getStatusCode());
        String history = get("/api/v1/documents/associations/" + id, "clinician-a", ORG_A).getBody();
        assertTrue(history.contains("\"link\""));
        assertTrue(history.contains("\"void\""));
        assertTrue(history.contains("wrong chart"));
    }

    @Test
    void rendersImmutableVersionedEnglishAndArabicPdfs() {
        String chart = registerChart(ORG_A, "reception-a");
        String en = render(ORG_A, "clinician-a", chart, "en", "Visit summary");
        String ar = render(ORG_A, "clinician-a", chart, "ar", "\u0644\u062e\u0635\u0629");
        ResponseEntity<String> enMeta = get("/api/v1/documents/rendered/" + en, "clinician-a", ORG_A);
        ResponseEntity<String> arMeta = get("/api/v1/documents/rendered/" + ar, "clinician-a", ORG_A);
        assertEquals("LTR", field(enMeta.getBody(), "direction"));
        assertEquals("RTL", field(arMeta.getBody(), "direction"));
        assertEquals("1", field(enMeta.getBody(), "version"));
        assertEquals("1", field(arMeta.getBody(), "version"));
        assertEquals(64, field(enMeta.getBody(), "sha256").length());

        ResponseEntity<byte[]> enBytes = content(en, "clinician-a", ORG_A);
        ResponseEntity<byte[]> arBytes = content(ar, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, enBytes.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, enBytes.getHeaders().getContentType());
        assertTrue(new String(enBytes.getBody(), StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
        assertTrue(new String(arBytes.getBody(), StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
        assertFalse(java.util.Arrays.equals(enBytes.getBody(), arBytes.getBody()));
        assertArrayEquals(enBytes.getBody(),
                content(en, "clinician-a", ORG_A).getBody(), "a stored revision never changes");

        String en2 = render(ORG_A, "clinician-a", chart, "en", "Visit summary");
        assertNotEquals(en, en2);
        assertEquals("2", field(get("/api/v1/documents/rendered/" + en2,
                "clinician-a", ORG_A).getBody(), "version"));
        String versions = get("/api/v1/documents/rendered?subjectType=chart&subjectId=" + chart
                + "&locale=en", "clinician-a", ORG_A).getBody();
        assertTrue(versions.contains("\"version\":1"));
        assertTrue(versions.contains("\"version\":2"));
        assertFalse(versions.contains("\"version\":3"));
    }

    @Test
    void concealsRendersAndOriginalsAcrossTenants() {
        String chartA = registerChart(ORG_A, "reception-a");
        String en = render(ORG_A, "clinician-a", chartA, "en", "Private");
        String id = link(ORG_A, "clinician-a", "chart", chartA, "application/pdf", ENCODED);
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/rendered/" + en, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                content(en, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/associations/" + id, "clinician-b", ORG_B).getStatusCode());
    }
}
