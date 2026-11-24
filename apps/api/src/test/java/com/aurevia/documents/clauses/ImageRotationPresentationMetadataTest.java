package com.aurevia.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * CLN-07 clause 3 acceptance on real PostgreSQL: image rotation is presentation
 * metadata, never a transformation of the retained original (synthetic data).
 *
 * <p>The rule is proved through the real HTTP entry points: a rotated link is
 * stored in its own bounded column while the database-owned SHA-256 still
 * describes the exact original bytes, a download returns those same bytes, and
 * rendering the same source at zero and ninety degrees writes a different
 * {@code /Rotate} value into a distinct immutable PDF without altering the
 * source association. Refusal paths cover an unsupported quarter turn, a peer
 * tenant and a role without render permission. Fixtures use two synthetic
 * tenants with distinct subjects and a fresh MRN per chart; the test owns and
 * drops its own database. No human, clinical or real-patient evidence is
 * claimed here.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_image_rotation_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ImageRotationPresentationMetadataTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "aurevia_image_rotation_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CLINICIAN = "clinician-a";
    private static final String RECEPTION = "reception-a";
    private static final String PEER = "clinician-b";
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original bytes".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);
    // PNG signature followed by synthetic ASCII, so an image original is neither empty nor huge.
    private static final byte[] IMAGE_BYTES = HexFormat.of().parseHex(
            "89504e470d0a1a0a53594e54484554494320494d414745");
    private static final String ENCODED_IMAGE = Base64.getEncoder().encodeToString(IMAGE_BYTES);

    private RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, CLINICIAN, "clinician");
            seed(statement, ORG_A, RECEPTION, "reception");
            seed(statement, ORG_B, PEER, "clinician");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Seed one synthetic subject with an active membership and one role in its tenant. */
    private static void seed(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + org.substring(0, 4) + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<byte[]> getBytes(String path, String subject, String org) {
        return rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(byte[].class);
    }

    /** Register a chart with an issuer-scoped, unique synthetic MRN. */
    private String registerChart() {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-ROT-"
                + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = post("/api/v1/patients", body, RECEPTION, ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /** Build a link command whose rotation is the only presentation field under test. */
    private static String linkBody(String chartId, int rotation) {
        return "{\"subjectType\":\"chart\",\"subjectId\":\"" + chartId
                + "\",\"fileName\":\"scan.pdf\",\"mediaType\":\"application/pdf\","
                + "\"contentBase64\":\"" + ENCODED + "\",\"rotationDegrees\":" + rotation + "}";
    }

    /** Link the synthetic original at the given rotation and return the association id. */
    private String link(String chartId, int rotation) {
        ResponseEntity<String> response = post("/api/v1/documents/associations",
                linkBody(chartId, rotation), CLINICIAN, ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /**
     * Link a synthetic PNG original at the given presentation rotation.
     *
     * @param chartId  freshly registered synthetic chart
     * @param rotation quarter turn to record as metadata
     * @return the new association id
     */
    private String linkImage(String chartId, int rotation) {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chartId
                + "\",\"fileName\":\"scan.png\",\"mediaType\":\"image/png\","
                + "\"contentBase64\":\"" + ENCODED_IMAGE + "\",\"rotationDegrees\":" + rotation + "}";
        ResponseEntity<String> response = post("/api/v1/documents/associations",
                body, CLINICIAN, ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /** Build a render command for the linked source at the given presentation rotation. */
    private static String renderBody(String chartId, int rotation, String sourceAssociationId) {
        return "{\"subjectType\":\"chart\",\"subjectId\":\"" + chartId
                + "\",\"locale\":\"en\",\"title\":\"Rotation summary\","
                + "\"lines\":[\"Synthetic line\"],\"sourceSystem\":\"SYNTHETIC-LAB\","
                + "\"sourceId\":\"REQ-1\",\"effectiveAt\":\"2026-11-19T10:00:00Z\","
                + "\"rotationDegrees\":" + rotation + ",\"sourceAssociationId\":"
                + (sourceAssociationId == null ? "null" : "\"" + sourceAssociationId + "\"") + "}";
    }

    /** Return a flat JSON field from a response body without a Jackson dependency. */
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

    /** Count associations for one synthetic subject directly, to prove a refused link wrote nothing. */
    private static int associationCount(String chartId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, ADMIN, "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM public.document_association "
                             + "WHERE organization_id = CAST(? AS uuid) AND subject_id = CAST(? AS uuid)")) {
            query.setString(1, ORG_A);
            query.setString(2, chartId);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String asAscii(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    /**
     * A rotated link records the quarter turn as metadata while the stored digest
     * still describes the original bytes and a download returns those exact bytes.
     */
    @Test
    void rotationIsStoredAsMetadataWithoutChangingTheRetainedBytes() throws Exception {
        String associationId = link(registerChart(), 90);

        ResponseEntity<String> view = get("/api/v1/documents/associations/" + associationId,
                CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode(), view.getBody());
        String body = view.getBody();
        assertEquals("90", field(body, "rotationDegrees"));
        assertEquals("active", field(body, "status"));
        assertEquals(String.valueOf(ORIGINAL.length), field(body, "byteSize"));
        assertEquals(digest(ORIGINAL), field(body, "sha256"),
                "the checksum must be derived by the database from the retained original bytes");
        assertFalse(body.contains("contentBase64"), "original bytes must not leak into the view");

        ResponseEntity<byte[]> content = getBytes("/api/v1/documents/associations/" + associationId
                + "/content", CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, content.getStatusCode());
        assertArrayEquals(ORIGINAL, content.getBody(),
                "a presentation rotation must never transform the retained original");
    }

    /**
     * Rendering a linked source at zero and ninety degrees yields distinct PDFs
     * with the matching /Rotate marker, and leaves the source association's
     * rotation, digest and original bytes unchanged.
     */
    @Test
    void rotatedRenderDiffersWhileSourceBytesStayIdentical() throws Exception {
        String chartId = registerChart();
        String associationId = link(chartId, 90);
        ResponseEntity<String> beforeResponse = get("/api/v1/documents/associations/" + associationId,
                CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, beforeResponse.getStatusCode(), beforeResponse.getBody());
        String before = beforeResponse.getBody();
        String sourceDigest = field(before, "sha256");
        assertEquals("90", field(before, "rotationDegrees"));

        ResponseEntity<String> zero = post("/api/v1/documents/rendered",
                renderBody(chartId, 0, associationId), CLINICIAN, ORG_A);
        assertEquals(HttpStatus.CREATED, zero.getStatusCode(), zero.getBody());
        ResponseEntity<String> ninety = post("/api/v1/documents/rendered",
                renderBody(chartId, 90, associationId), CLINICIAN, ORG_A);
        assertEquals(HttpStatus.CREATED, ninety.getStatusCode(), ninety.getBody());

        ResponseEntity<byte[]> zeroContent = getBytes("/api/v1/documents/rendered/"
                + field(zero.getBody(), "id") + "/content", CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, zeroContent.getStatusCode());
        byte[] zeroPdf = zeroContent.getBody();
        ResponseEntity<byte[]> ninetyContent = getBytes("/api/v1/documents/rendered/"
                + field(ninety.getBody(), "id") + "/content", CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, ninetyContent.getStatusCode());
        byte[] ninetyPdf = ninetyContent.getBody();
        assertFalse(Arrays.equals(zeroPdf, ninetyPdf),
                "two presentation rotations are two distinct immutable artifacts");
        assertTrue(asAscii(zeroPdf).contains("/Rotate 0 /Resources"));
        assertTrue(asAscii(ninetyPdf).contains("/Rotate 90 /Resources"));
        assertTrue(asAscii(ninetyPdf).startsWith("%PDF-1.4"));
        assertEquals(digest(ninetyPdf), field(ninety.getBody(), "sha256"),
                "each immutable render owns a database-derived digest of its own bytes");

        ResponseEntity<String> afterResponse = get("/api/v1/documents/associations/" + associationId,
                CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, afterResponse.getStatusCode(), afterResponse.getBody());
        String after = afterResponse.getBody();
        assertEquals(sourceDigest, field(after, "sha256"),
                "rendering must not change the retained original's checksum");
        assertEquals("90", field(after, "rotationDegrees"));
        ResponseEntity<byte[]> sourceContent = getBytes("/api/v1/documents/associations/" + associationId
                + "/content", CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, sourceContent.getStatusCode());
        assertArrayEquals(ORIGINAL, sourceContent.getBody(),
                "rendering must not mutate the retained original bytes");
    }

    /** An unsupported quarter turn is refused at the boundary and creates no association. */
    @Test
    void unsupportedRotationIsRefused() throws Exception {
        String chartId = registerChart();

        ResponseEntity<String> refused = post("/api/v1/documents/associations",
                linkBody(chartId, 45), CLINICIAN, ORG_A);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_ROTATION", field(refused.getBody(), "code"));
        assertEquals(0, associationCount(chartId),
                "a refused rotation must not leave a retained association behind");
    }

    /** A peer tenant cannot read the rotated association's metadata or its original bytes. */
    @Test
    void peerTenantCannotReadTheRotatedAssociation() throws Exception {
        String associationId = link(registerChart(), 90);

        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/documents/associations/" + associationId, PEER, ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                getBytes("/api/v1/documents/associations/" + associationId + "/content",
                        PEER, ORG_B).getStatusCode());

        ResponseEntity<String> owner = get("/api/v1/documents/associations/" + associationId,
                CLINICIAN, ORG_A);
        assertEquals("90", field(owner.getBody(), "rotationDegrees"),
                "the owning tenant still sees its own presentation metadata");
    }

    /** Reception may link and read but cannot render a rotated PDF. */
    @Test
    void receptionCannotRenderARotatedPdf() throws Exception {
        String chartId = registerChart();
        String associationId = link(chartId, 90);

        ResponseEntity<String> refused = post("/api/v1/documents/rendered",
                renderBody(chartId, 90, associationId), RECEPTION, ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", field(refused.getBody(), "code"));
    }

    /**
     * An image original keeps its exact bytes: rotation is recorded as metadata
     * beside the database-derived digest and is never applied to the image.
     */
    @Test
    void imageRotationIsPresentationMetadataForImageOriginals() throws Exception {
        String associationId = linkImage(registerChart(), 90);

        ResponseEntity<String> view = get("/api/v1/documents/associations/" + associationId,
                CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode(), view.getBody());
        String body = view.getBody();
        assertEquals("image/png", field(body, "mediaType"));
        assertEquals("90", field(body, "rotationDegrees"));
        assertEquals(String.valueOf(IMAGE_BYTES.length), field(body, "byteSize"));
        assertEquals(digest(IMAGE_BYTES), field(body, "sha256"),
                "the database digest must describe the exact retained image bytes");
        assertFalse(body.contains("contentBase64"), "original image bytes must not leak into the view");

        ResponseEntity<byte[]> content = getBytes("/api/v1/documents/associations/" + associationId
                + "/content", CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, content.getStatusCode());
        assertArrayEquals(IMAGE_BYTES, content.getBody(),
                "a presentation rotation must never transform the retained image");
    }

    /**
     * An unsupported rotation is refused on the render path before any immutable
     * PDF is written, so the subject has no rendered version afterwards.
     */
    @Test
    void invalidRenderRotationIsRefused() {
        String chartId = registerChart();
        String associationId = link(chartId, 90);

        ResponseEntity<String> refused = post("/api/v1/documents/rendered",
                renderBody(chartId, 45, associationId), CLINICIAN, ORG_A);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_ROTATION", field(refused.getBody(), "code"));
        ResponseEntity<String> renders = get("/api/v1/documents/rendered?subjectType=chart"
                + "&subjectId=" + chartId + "&locale=en", CLINICIAN, ORG_A);
        assertEquals(HttpStatus.OK, renders.getStatusCode(), renders.getBody());
        assertEquals("[]", renders.getBody(),
                "a refused render rotation must not leave an immutable artifact behind");
    }
}
