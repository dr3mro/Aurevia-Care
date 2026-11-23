package com.aurevia.smartclinic.documents.clauses;

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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
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

/**
 * CLN-07 clause 5 (API evidence): generate immutable versioned PDFs in English
 * or Arabic with embedded fonts, direction-aware layout, page numbers,
 * provenance and print profile.
 *
 * <p>The positive test drives the real HTTP entry points
 * {@code POST /api/v1/documents/rendered} and
 * {@code GET /api/v1/documents/rendered/{id}[/content]} against PostgreSQL and
 * asserts every observable token of the clause: locale-derived layout
 * direction, append-only versioning, a database-owned SHA-256 of the exact
 * retained bytes, the PDF structure markers for an embedded font and a print
 * profile, a page number, source provenance, byte-for-byte immutability on
 * re-fetch and a fresh id and version on the next render. The negative tests
 * assert that the rule refuses what it must refuse: a role without render
 * permission, a caller from another tenant and a locale outside the supported
 * set.</p>
 *
 * <p>This clause note proves the structure and direction the renderer
 * produces. It deliberately does not claim real font glyph shaping or Arabic
 * typography: the renderer's embedded font program is a synthetic placeholder
 * and production glyph handling belongs to an approved rendering library.</p>
 *
 * <p>Synthetic fixtures only; every row is confined to its organization by
 * forced row-level security. The test owns a distinct database so a parallel
 * worker cannot perturb the version sequence.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_pdf_clause_it")
@ActiveProfiles("synthetic-auth")
class GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_pdf_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String TITLE_EN = "Visit summary";
    private static final String TITLE_AR = "\u0644\u062e\u0635\u0629";
    private static final String SOURCE = "source:SYNTHETIC-LAB/REQ-1";

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate a private, Flyway-migrated database with two synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_doc_pdf_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_doc_pdf_clause_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_doc_pdf_clause_it WITH (FORCE)");
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
                                           String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        // Capture the error body as well as the status so refusals can assert a machine code.
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String uri, String body, String subject, String org) {
        return request(HttpMethod.POST, uri, body, subject, org);
    }

    private ResponseEntity<String> get(String uri, String subject, String org) {
        return request(HttpMethod.GET, uri, null, subject, org);
    }

    private ResponseEntity<byte[]> content(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/rendered/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(byte[].class);
    }

    private String registerChart(String org, String reception) {
        // A fresh issuer-scoped MRN keeps V7's (org, issuer, mrn, version) unique across tests.
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-" + org.substring(0, 2)
                + "-" + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = post("/api/v1/patients", body, reception, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private static String renderJson(String subjectId, String locale, String title) {
        return "{\"subjectType\":\"chart\",\"subjectId\":\"" + subjectId
                + "\",\"locale\":\"" + locale + "\",\"title\":\"" + title
                + "\",\"lines\":[\"Synthetic line\"],\"sourceSystem\":\"SYNTHETIC-LAB\","
                + "\"sourceId\":\"REQ-1\",\"effectiveAt\":\"2026-11-19T10:00:00Z\","
                + "\"rotationDegrees\":0,\"sourceAssociationId\":null}";
    }

    private String render(String org, String clinician, String subjectId, String locale, String title) {
        ResponseEntity<String> response = post("/api/v1/documents/rendered",
                renderJson(subjectId, locale, title), clinician, org);
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

    // The renderer writes arbitrary bytes, so ISO-8859-1 (not UTF-8) keeps each byte
    // inspectable as one character while its ASCII and hex markers stay searchable.
    private static String asAscii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    // Text inside the PDF is UTF-16BE hex in upper case, wrapped in angle brackets.
    private static String utf16Hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_16BE)).toUpperCase(Locale.ROOT);
    }

    /**
     * Positive evidence: one English and one Arabic render are immutable,
     * versioned and carry the embedded-font, print-profile, direction, page,
     * provenance and checksum tokens through the real HTTP entry points.
     */
    @Test
    void generatesImmutableVersionedEnglishAndArabicPdfsWithEmbeddedFontsPrintProfileAndProvenance()
            throws Exception {
        String chart = registerChart(ORG_A, "reception-a");
        String en = render(ORG_A, "clinician-a", chart, "en", TITLE_EN);
        String ar = render(ORG_A, "clinician-a", chart, "ar", TITLE_AR);

        ResponseEntity<String> enMeta = get("/api/v1/documents/rendered/" + en, "clinician-a", ORG_A);
        ResponseEntity<String> arMeta = get("/api/v1/documents/rendered/" + ar, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, enMeta.getStatusCode());
        assertEquals(HttpStatus.OK, arMeta.getStatusCode());
        assertEquals("LTR", field(enMeta.getBody(), "direction"));
        assertEquals("RTL", field(arMeta.getBody(), "direction"));
        assertEquals("1", field(enMeta.getBody(), "version"));
        assertEquals("1", field(arMeta.getBody(), "version"));
        assertEquals("SYNTHETIC-LAB/REQ-1", field(enMeta.getBody(), "provenance"));
        String sha256 = field(enMeta.getBody(), "sha256");
        assertEquals(64, sha256.length());
        assertTrue(sha256.matches("[0-9a-f]{64}"), "the checksum is a lower-case SHA-256 digest");

        ResponseEntity<byte[]> enBytes = content(en, "clinician-a", ORG_A);
        ResponseEntity<byte[]> arBytes = content(ar, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, enBytes.getStatusCode());
        assertEquals(HttpStatus.OK, arBytes.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, enBytes.getHeaders().getContentType());
        assertEquals(MediaType.APPLICATION_PDF, arBytes.getHeaders().getContentType());
        String enPdf = asAscii(enBytes.getBody());
        String arPdf = asAscii(arBytes.getBody());

        assertTrue(enPdf.startsWith("%PDF-1.4"), "a complete PDF is returned");
        assertTrue(arPdf.startsWith("%PDF-1.4"));
        assertTrue(enPdf.contains("/Type /Font /Subtype /TrueType"), "a font object is declared");
        assertTrue(enPdf.contains("/FontFile2 7 0 R"), "the font program is embedded by reference");
        assertTrue(enPdf.contains("/PrintScaling /None"), "the A4 print profile disables scaling");
        assertTrue(enPdf.contains(utf16Hex("Page 1 / 1")), "a page number is rendered");
        assertTrue(enPdf.contains("/Subject <" + utf16Hex(SOURCE) + ">"), "provenance is stamped in the Info dictionary");
        assertTrue(enPdf.contains("/Direction <" + utf16Hex("LTR") + ">"), "English is laid out left to right");
        assertTrue(arPdf.contains("/Direction <" + utf16Hex("RTL") + ">"), "Arabic is laid out right to left");
        assertTrue(arPdf.contains(utf16Hex(TITLE_AR)), "the original Arabic script is preserved");
        assertEquals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(enBytes.getBody())), sha256,
                "the stored digest is derived from the exact bytes served");

        assertFalse(Arrays.equals(enBytes.getBody(), arBytes.getBody()), "the two locales are distinct artifacts");
        assertArrayEquals(enBytes.getBody(), content(en, "clinician-a", ORG_A).getBody(),
                "re-fetching a revision returns identical immutable bytes");

        String en2 = render(ORG_A, "clinician-a", chart, "en", TITLE_EN);
        assertNotEquals(en, en2, "a new render owns a new id");
        assertEquals("2", field(get("/api/v1/documents/rendered/" + en2, "clinician-a", ORG_A).getBody(), "version"),
                "the next render of the same subject and locale increments the version");
        assertFalse(Arrays.equals(enBytes.getBody(), content(en2, "clinician-a", ORG_A).getBody()),
                "each version is a distinct immutable artifact");
    }

    /**
     * Negative evidence: reception may link and read documents but may not
     * render a clinical PDF, so its fully-formed render command is refused.
     */
    @Test
    void refusesRenderForAReceptionUserWhoLacksDocumentRender() {
        String chart = registerChart(ORG_A, "reception-a");
        // Input is validated before authorization: the body must be valid so a 403
        // proves the role denial rather than a 422 from a malformed command.
        ResponseEntity<String> refused = post("/api/v1/documents/rendered",
                renderJson(chart, "en", TITLE_EN), "reception-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", field(refused.getBody(), "code"));
    }

    /**
     * Negative evidence: forced row-level security conceals another tenant's
     * rendered revision from both its metadata and its immutable bytes.
     */
    @Test
    void concealsAnotherTenantsRenderedPdf() {
        String chartA = registerChart(ORG_A, "reception-a");
        String en = render(ORG_A, "clinician-a", chartA, "en", TITLE_EN);
        ResponseEntity<String> foreignMeta = get("/api/v1/documents/rendered/" + en, "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, foreignMeta.getStatusCode(), foreignMeta.getBody());
        assertEquals("NOT_FOUND", field(foreignMeta.getBody(), "code"));
        ResponseEntity<byte[]> foreignBytes = content(en, "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, foreignBytes.getStatusCode());
    }

    /**
     * Negative evidence: a locale outside the approved English/Arabic set is a
     * machine-readable validation refusal, not a silently mis-directed layout.
     */
    @Test
    void refusesAnUnsupportedRenderLocale() {
        String chart = registerChart(ORG_A, "reception-a");
        ResponseEntity<String> refused = post("/api/v1/documents/rendered",
                renderJson(chart, "de", TITLE_EN), "clinician-a", ORG_A);
        // Assert the numeric status: Spring 6 names 422 UNPROCESSABLE_CONTENT.
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_LOCALE", field(refused.getBody(), "code"));
    }
}
