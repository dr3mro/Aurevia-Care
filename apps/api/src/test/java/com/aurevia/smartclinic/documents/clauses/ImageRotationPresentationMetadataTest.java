package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * CLN-07 clause 3 HTTP and database acceptance for synthetic original images.
 *
 * <p>Image rotation is presentation metadata: a bounded quarter turn is accepted
 * and retained beside the association, while the original bytes and the
 * database-derived checksum never change. Rendering applies the turn as a PDF
 * page directive to a new immutable revision, not to the retained original.
 * Every assertion goes through the real controller on a random port and reads
 * durable rows back under forced row-level security, so a passing test proves
 * the reachable path rather than unit coverage. All identities, MRNs and image
 * bytes are synthetic; no patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln07_c03_it")
@ActiveProfiles("synthetic-auth")
class ImageRotationPresentationMetadataTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln07_c03_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final byte[] ORIGINAL = syntheticPng();
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);

    RestClient rest;

    /**
     * Build the HTTP client against the randomly assigned server port.
     *
     * @param port port chosen by Spring Boot for this test context
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create a private database, apply every Flyway migration and seed three
     * synthetic staff members across two tenants.
     *
     * @throws Exception when database setup fails
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
            try (ResultSet roles = statement.executeQuery(
                    "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'smartclinic_app'")) {
                assertTrue(roles.next());
                assertFalse(roles.getBoolean(1),
                        "the readback role must not bypass RLS for the concealment assertion");
            }
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln07_c03_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln07_c03_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    /**
     * Drop the private database so parallel clause workers cannot collide.
     *
     * @throws Exception when database teardown fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln07_c03_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
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

    private static byte[] syntheticPng() {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        byte[] body = "SYNTHETIC-ROTATION-FIXTURE-NO-PHI".getBytes(StandardCharsets.UTF_8);
        byte[] image = Arrays.copyOf(signature, signature.length + body.length);
        System.arraycopy(body, 0, image, signature.length, body.length);
        return image;
    }

    private String registerChart() {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-"
                + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = post("/api/v1/patients", body, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private ResponseEntity<String> link(String chartId, Integer rotation) {
        StringBuilder body = new StringBuilder("{\"subjectType\":\"chart\",\"subjectId\":\"")
                .append(chartId).append("\",\"fileName\":\"scan.png\",\"mediaType\":\"image/png\",")
                .append("\"contentBase64\":\"").append(ENCODED).append("\"");
        if (rotation != null) {
            body.append(",\"rotationDegrees\":").append(rotation);
        }
        return post("/api/v1/documents/associations", body.append('}').toString(), "clinician-a", ORG_A);
    }

    private ResponseEntity<String> association(String id) {
        return rest.get().uri("/api/v1/documents/associations/" + id)
                .header("X-Synthetic-Subject", "clinician-a")
                .header("X-Active-Organization", ORG_A)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<byte[]> content(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/associations/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(byte[].class);
    }

    private ResponseEntity<String> render(String chartId, Integer rotation, String sourceAssociationId,
                                          String subject, String org) {
        StringBuilder body = new StringBuilder("{\"subjectType\":\"chart\",\"subjectId\":\"")
                .append(chartId).append("\",\"locale\":\"en\",\"title\":\"Synthetic Report\",")
                .append("\"lines\":[\"Line one\",\"Line two\"],\"sourceSystem\":\"SYNTH\",")
                .append("\"sourceId\":\"SYN-1\",\"effectiveAt\":\"2026-11-23T00:00:00Z\"");
        if (rotation != null) {
            body.append(",\"rotationDegrees\":").append(rotation);
        }
        if (sourceAssociationId != null) {
            body.append(",\"sourceAssociationId\":\"").append(sourceAssociationId).append('"');
        }
        return post("/api/v1/documents/rendered", body.append('}').toString(), subject, org);
    }

    private ResponseEntity<byte[]> renderedContent(String renderId) {
        return rest.get().uri("/api/v1/documents/rendered/" + renderId + "/content")
                .header("X-Synthetic-Subject", "clinician-a")
                .header("X-Active-Organization", ORG_A)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(byte[].class);
    }

    private ResponseEntity<String> post(String uri, String body, String subject, String org) {
        return rest.post().uri(uri)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", "cln07-c03-corr")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /**
     * Read the retained quarter turn under the tenant's forced RLS policy.
     *
     * @param id  opaque association identifier
     * @param org tenant whose row-level policy must be applied
     * @return the stored degrees, or {@code null} when the row is concealed
     * @throws Exception when the guarded read fails
     */
    private static Integer storedRotation(String id, String org) throws Exception {
        // smartclinic_app is NOBYPASSRLS, so FORCE RLS applies even to a read by
        // the connection: a foreign tenant sees no row rather than a leaked one.
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            statement.execute("SET app.purpose = 'DOCUMENT_READ'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT rotation_degrees FROM public.document_association WHERE id = CAST('"
                            + id + "' AS uuid)")) {
                return result.next() ? Integer.valueOf(result.getInt(1)) : null;
            }
        }
    }

    /**
     * Try to rewrite the retained turn directly, as a bypass attempt would.
     *
     * @param id   opaque association identifier
     * @param org  tenant that owns the row
     * @param turns proposed replacement value
     * @throws SQLException when the database guard refuses the rewrite, as expected
     */
    private static void rewriteRotation(String id, String org, int turns) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            statement.execute("UPDATE public.document_association SET rotation_degrees = " + turns
                    + " WHERE id = CAST('" + id + "' AS uuid)");
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String utf16Hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_16BE)).toUpperCase(Locale.ROOT);
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') {
            start++;
        }
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static int intField(String json, String key) {
        return Integer.parseInt(field(json, key));
    }

    /**
     * Prove that a bounded quarter turn is kept as metadata while the original
     * PNG bytes and their checksum stay identical through link, download and
     * two renders with different presentation rotations.
     *
     * @throws Exception when a guarded database read fails
     */
    @Test
    void rotationIsStoredAsMetadataWithoutChangingOriginalBytes() throws Exception {
        String chartId = registerChart();
        ResponseEntity<String> linked = link(chartId, 90);
        assertEquals(HttpStatus.CREATED, linked.getStatusCode(), linked.getBody());
        String id = field(linked.getBody(), "id");

        ResponseEntity<String> found = association(id);
        assertEquals(HttpStatus.OK, found.getStatusCode(), found.getBody());
        String view = found.getBody();
        assertEquals(90, intField(view, "rotationDegrees"));
        assertEquals("image/png", field(view, "mediaType"));
        assertEquals(ORIGINAL.length, Long.parseLong(field(view, "byteSize")));
        assertEquals(sha256(ORIGINAL), field(view, "sha256"));

        ResponseEntity<byte[]> original = content(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, original.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, original.getHeaders().getContentType());
        assertArrayEquals(ORIGINAL, original.getBody());
        assertEquals(Integer.valueOf(90), storedRotation(id, ORG_A));

        ResponseEntity<String> turned = render(chartId, 90, id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, turned.getStatusCode(), turned.getBody());
        assertEquals(1, intField(turned.getBody(), "version"));
        byte[] turnedBytes = renderedContent(field(turned.getBody(), "id")).getBody();
        assertNotNull(turnedBytes);
        String turnedText = new String(turnedBytes, StandardCharsets.ISO_8859_1);
        assertTrue(turnedText.contains("/Rotate 90 /Resources <<"),
                "rotation must be a PDF page directive, not a byte rewrite");
        assertTrue(turnedText.contains(utf16Hex("association:" + id)),
                "the immutable render must record its linked original as provenance");

        // A second fresh chart in the same tenant keeps both renders at version 1;
        // the /Rotate tokens below carry the causal rotation proof.
        String plainChart = registerChart();
        ResponseEntity<String> straight = render(plainChart, 0, null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, straight.getStatusCode(), straight.getBody());
        assertEquals(1, intField(straight.getBody(), "version"));
        byte[] straightBytes = renderedContent(field(straight.getBody(), "id")).getBody();
        assertNotNull(straightBytes);
        String straightText = new String(straightBytes, StandardCharsets.ISO_8859_1);
        assertTrue(straightText.contains("/Rotate 0 /Resources <<"));
        assertFalse(straightText.contains("/Rotate 90 /Resources <<"));
        assertFalse(Arrays.equals(turnedBytes, straightBytes),
                "each immutable render must be its own revision artifact");

        assertArrayEquals(ORIGINAL, content(id, "clinician-a", ORG_A).getBody(),
                "rendering must never rewrite the retained original");
        assertEquals(Integer.valueOf(90), storedRotation(id, ORG_A));
        assertEquals(sha256(ORIGINAL), field(association(id).getBody(), "sha256"));
    }

    /**
     * Reject a rotation outside the four quarter turns before any row is written.
     */
    @Test
    void linkRejectsRotationOutsideQuarterTurns() {
        String chartId = registerChart();
        ResponseEntity<String> rejected = link(chartId, 45);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, rejected.getStatusCode(), rejected.getBody());
        assertEquals("INVALID_ROTATION", field(rejected.getBody(), "code"));
        assertFalse(rejected.getBody().contains(ENCODED), "an error must not echo the original");
    }

    /**
     * A receptionist may link and read originals but has no render permission.
     */
    @Test
    void renderIsForbiddenForRoleWithoutDocumentPermission() {
        String chartId = registerChart();
        ResponseEntity<String> rejected = render(chartId, 90, null, "reception-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode(), rejected.getBody());
        assertEquals("FORBIDDEN", field(rejected.getBody(), "code"));
        assertEquals("missing document permission", field(rejected.getBody(), "message"),
                "reception has an active membership but no render permission");
        assertFalse(rejected.getBody().contains(ENCODED), "an error must not echo the original");
    }

    /**
     * Conceal an original and its rotation from a clinician in another tenant.
     *
     * @throws Exception when a guarded database read fails
     */
    @Test
    void contentIsConcealedFromAnotherTenant() throws Exception {
        String id = field(link(registerChart(), 90).getBody(), "id");
        assertEquals(HttpStatus.OK, content(id, "clinician-a", ORG_A).getStatusCode());

        ResponseEntity<byte[]> foreign = content(id, "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode());
        assertEquals("NOT_FOUND", field(new String(foreign.getBody() == null ? new byte[0] : foreign.getBody(),
                StandardCharsets.UTF_8), "code"));
        assertNull(storedRotation(id, ORG_B), "forced RLS must conceal the other tenant's row");
        assertEquals(Integer.valueOf(90), storedRotation(id, ORG_A));
    }

    /**
     * Reject an invalid render rotation at the same boundary guard as linking.
     */
    @Test
    void renderRejectsInvalidRotation() {
        String chartId = registerChart();
        ResponseEntity<String> rejected = render(chartId, 45, null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, rejected.getStatusCode(), rejected.getBody());
        assertEquals("INVALID_ROTATION", field(rejected.getBody(), "code"));
        assertFalse(rejected.getBody().contains(ENCODED), "an error must not echo the original");
    }

    /**
     * Treat an omitted rotation as zero, the neutral presentation value.
     *
     * @throws Exception when a guarded database read fails
     */
    @Test
    void omittedRotationDefaultsToZero() throws Exception {
        String chartId = registerChart();
        ResponseEntity<String> linked = link(chartId, null);
        assertEquals(HttpStatus.CREATED, linked.getStatusCode(), linked.getBody());
        String id = field(linked.getBody(), "id");
        assertEquals(0, intField(association(id).getBody(), "rotationDegrees"));
        assertEquals(Integer.valueOf(0), storedRotation(id, ORG_A));
        assertArrayEquals(ORIGINAL, content(id, "clinician-a", ORG_A).getBody());

        ResponseEntity<String> rendered = render(chartId, null, null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, rendered.getStatusCode(), rendered.getBody());
        byte[] bytes = renderedContent(field(rendered.getBody(), "id")).getBody();
        assertNotNull(bytes);
        String pdf = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(pdf.contains("/Rotate 0 /Resources <<"));
        assertFalse(pdf.contains("/Rotate 90 /Resources <<"));
    }

    /**
     * Refuse a direct database rewrite of a stored quarter turn, so rotation
     * stays presentation metadata for the lifetime of the association.
     *
     * @throws Exception when the guarded checks fail
     */
    @Test
    void rotationCannotBeRewrittenAfterLinking() throws Exception {
        String id = field(link(registerChart(), 180).getBody(), "id");
        assertEquals(Integer.valueOf(180), storedRotation(id, ORG_A));
        assertThrows(SQLException.class, () -> rewriteRotation(id, ORG_A, 90),
                "the association guard and column grants must reject a rotation rewrite");
        assertEquals(Integer.valueOf(180), storedRotation(id, ORG_A));
        assertArrayEquals(ORIGINAL, content(id, "clinician-a", ORG_A).getBody());
    }

    /**
     * Accept exactly the four bounded quarter turns for both link and render.
     *
     * @throws Exception when a guarded database read fails
     */
    @Test
    void everyBoundedQuarterTurnIsAcceptedAndRetained() throws Exception {
        for (int turns : new int[] {0, 90, 180, 270}) {
            String chartId = registerChart();
            ResponseEntity<String> linked = link(chartId, turns);
            assertEquals(HttpStatus.CREATED, linked.getStatusCode(), linked.getBody());
            String id = field(linked.getBody(), "id");
            assertEquals(turns, intField(association(id).getBody(), "rotationDegrees"));
            assertEquals(Integer.valueOf(turns), storedRotation(id, ORG_A));
            assertArrayEquals(ORIGINAL, content(id, "clinician-a", ORG_A).getBody());

            ResponseEntity<String> rendered = render(chartId, turns, id, "clinician-a", ORG_A);
            assertEquals(HttpStatus.CREATED, rendered.getStatusCode(), rendered.getBody());
            byte[] bytes = renderedContent(field(rendered.getBody(), "id")).getBody();
            assertNotNull(bytes);
            String pdf = new String(bytes, StandardCharsets.ISO_8859_1);
            assertTrue(pdf.contains("/Rotate " + turns + " /Resources <<"));
        }
    }
}
