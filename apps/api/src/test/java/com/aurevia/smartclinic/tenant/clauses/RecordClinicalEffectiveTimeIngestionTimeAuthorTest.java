package com.aurevia.smartclinic.tenant.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * ARC-01 clause 6 (API evidence): a tenant-owned clinical record carries the
 * clinical effective time, the ingestion time, the clinical author, the
 * data-entry actor and the record version, and the running create route refuses
 * provenance it must not accept.
 *
 * <p>The positive case goes through {@code POST /api/v1/clinical-notes} against a
 * fresh database created from the production Flyway migrations, then reads the
 * persisted revision row to prove the values are durable and not only present in
 * the response. A future or malformed effective instant, an actor without a note
 * role, a cross-tenant read or write, a stale version precondition and an
 * attempted override of the data-entry actor are each refused with an asserted
 * status and no or unchanged stored row.</p>
 *
 * <p>All fixtures are synthetic: no real identity or patient data is used. The
 * source-system, source-ID and published-contract aspects of the clause are not
 * carried by this route; the companion evidence document records them as a gap
 * instead of leaving a disabled or failing test here.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_clinical_provenance_it")
@ActiveProfiles("synthetic-auth")
class RecordClinicalEffectiveTimeIngestionTimeAuthorTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DATABASE = "smartclinic_clinical_provenance_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic memberships and one synthetic patient.
     *
     * @throws Exception when the database cannot be created or seeded
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "clinician-b", "clinician");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "clinician-other", "clinician");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            // V7 requires a civil date or an explicit unknown reason; use a
            // synthetic date so the append-only patient invariant holds.
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                    + "dob, language, contacts, identifiers, consents) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT_A + "', 1, 'MRN-PROVENANCE', 'CLINIC-A', "
                    + "'Synthetic Provenance Patient', 'synthetic provenance patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    /**
     * Drop the fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
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
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status, valid_from) "
                + "SELECT '" + org + "', id, 'active', '2020-01-01T00:00:00Z'::timestamptz "
                + "FROM public.user_identity WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = '" + org + "'");
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

    private static String createBody(String content, String effectiveAt, String author,
                                     int templateVersion, String extraJson) {
        return "{\"patientId\":\"" + PATIENT_A + "\",\"title\":\"Provenance note\","
                + "\"content\":\"" + content + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":" + templateVersion + ","
                + "\"clinicalAuthorSubject\":\"" + author + "\""
                + (effectiveAt == null ? "" : ",\"effectiveAt\":\"" + effectiveAt + "\"")
                + (extraJson == null ? "" : "," + extraJson) + "}";
    }

    private ResponseEntity<String> create(String subject, String org, String content,
                                          String effectiveAt, String author, int templateVersion) {
        return create(subject, org, content, effectiveAt, author, templateVersion, null);
    }

    private ResponseEntity<String> create(String subject, String org, String content,
                                          String effectiveAt, String author, int templateVersion,
                                          String extraJson) {
        return request(HttpMethod.POST, "/api/v1/clinical-notes",
                createBody(content, effectiveAt, author, templateVersion, extraJson), subject, org, null);
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
     * The persisted provenance columns of one synthetic revision, read inside the
     * owning tenant. The migration owner is subject to FORCE RLS too, so the
     * tenant must be bound before the read.
     */
    private record StoredRevision(String clinicalAuthorSubject, String dataEntrySubject,
                                  OffsetDateTime effectiveAt, OffsetDateTime createdAt,
                                  int version, int templateVersion) {
    }

    private static StoredRevision stored(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_author_subject, data_entry_subject, effective_at, created_at, "
                            + "version, template_version FROM public.clinical_note_revision "
                            + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next(), "the synthetic revision must be persisted");
                return new StoredRevision(result.getString("clinical_author_subject"),
                        result.getString("data_entry_subject"),
                        result.getObject("effective_at", OffsetDateTime.class),
                        result.getObject("created_at", OffsetDateTime.class),
                        result.getInt("version"), result.getInt("template_version"));
            }
        }
    }

    private static int countRowsByContent(String content) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.clinical_note_revision WHERE content = '" + content + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static int countRevisions(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.clinical_note_revision "
                            + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void createRecordsEffectiveTimeAuthorAndVersionWithIngestionTime() throws Exception {
        String effective = "2024-06-01T10:15:30Z";
        ResponseEntity<String> created = create("clinician-a", ORG_A,
                "provenance positive note", effective, "clinician-a", 2);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String body = created.getBody();
        // PostgreSQL renders timestamptz in the session time zone and can omit
        // zero offset minutes. Preserve the assertion on the full instant.
        DateTimeFormatter postgresTimestamp = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ISO_LOCAL_DATE)
                .appendLiteral(' ')
                .append(DateTimeFormatter.ISO_LOCAL_TIME)
                .appendOffset("+HH:mm", "Z")
                .toFormatter();
        String returnedEffectiveAt = field(body, "effectiveAt");
        assertEquals(Instant.parse(effective),
                OffsetDateTime.parse(returnedEffectiveAt, postgresTimestamp).toInstant(),
                "the clinical effective time must be returned");
        assertEquals("clinician-a", field(body, "clinicalAuthorSubject"));
        assertEquals("clinician-a", field(body, "dataEntrySubject"));
        assertFalse(field(body, "createdAt").isEmpty(), "the ingestion instant must be recorded");
        assertEquals("1", field(body, "version"));
        assertEquals("2", field(body, "templateVersion"));

        StoredRevision stored = stored(field(body, "id"));
        assertEquals("clinician-a", stored.clinicalAuthorSubject());
        assertEquals("clinician-a", stored.dataEntrySubject());
        assertNotNull(stored.effectiveAt());
        assertEquals(Instant.parse(effective), stored.effectiveAt().toInstant());
        assertNotNull(stored.createdAt());
        assertTrue(stored.createdAt().isAfter(stored.effectiveAt()),
                "ingestion must be stamped later than the clinical effective time");
        assertEquals(1, stored.version());
        assertEquals(2, stored.templateVersion());
    }

    @Test
    void futureOrMalformedEffectiveTimeIsRefused() throws Exception {
        String future = Instant.now().plusSeconds(3600).toString();
        ResponseEntity<String> futureResult = create("clinician-a", ORG_A,
                "provenance future note", future, "clinician-a", 1);
        assertEquals(422, futureResult.getStatusCode().value(), futureResult.getBody());
        assertEquals("INVALID_EFFECTIVE_AT", field(futureResult.getBody(), "code"), futureResult.getBody());

        ResponseEntity<String> malformed = create("clinician-a", ORG_A,
                "provenance malformed note", "not-an-instant", "clinician-a", 1);
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertEquals("INVALID_EFFECTIVE_AT", field(malformed.getBody(), "code"), malformed.getBody());

        assertEquals(0, countRowsByContent("provenance future note"));
        assertEquals(0, countRowsByContent("provenance malformed note"));
    }

    @Test
    void actorWithoutANoteRoleCannotRecordProvenance() throws Exception {
        ResponseEntity<String> denied = create("reception-a", ORG_A,
                "provenance wrong role note", "2024-06-01T10:15:30Z", "clinician-a", 1);
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"), denied.getBody());
        assertEquals(0, countRowsByContent("provenance wrong role note"));
    }

    @Test
    void crossTenantActorCannotReadOrWriteTheRecord() throws Exception {
        ResponseEntity<String> created = create("clinician-a", ORG_A,
                "provenance tenant note", "2024-06-01T10:15:30Z", "clinician-a", 1);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> read = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-other", ORG_B, null);
        assertEquals(404, read.getStatusCode().value(), read.getBody());
        ResponseEntity<String> edit = request(HttpMethod.PUT,
                "/api/v1/clinical-notes/" + id,
                "{\"title\":\"Stolen\",\"content\":\"Stolen content\"}",
                "clinician-other", ORG_B, "\"1\"");
        assertEquals(404, edit.getStatusCode().value(), edit.getBody());

        StoredRevision stored = stored(id);
        assertEquals(1, stored.version());
        assertEquals("clinician-a", stored.clinicalAuthorSubject());
        assertEquals(0, countRowsByContent("Stolen content"));
        assertEquals(1, countRevisions(id));
    }

    @Test
    void staleVersionIsRefused() throws Exception {
        ResponseEntity<String> created = create("clinician-a", ORG_A,
                "provenance stale note", "2024-06-01T10:15:30Z", "clinician-a", 1);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> stale = request(HttpMethod.PUT,
                "/api/v1/clinical-notes/" + id,
                "{\"title\":\"Late\",\"content\":\"Late content\"}",
                "clinician-a", ORG_A, "\"9\"");
        assertEquals(412, stale.getStatusCode().value(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals(1, stored(id).version());
        assertEquals(1, countRevisions(id));
    }

    @Test
    void dataEntryActorAndIngestionTimeCannotBeForgedFromTheRequestBody() throws Exception {
        String extra = "\"dataEntrySubject\":\"clinician-b\",\"createdAt\":\"1999-01-01T00:00:00Z\"";
        ResponseEntity<String> created = create("clinician-a", ORG_A,
                "provenance forged actor note", "2024-06-01T10:15:30Z", "clinician-a", 1, extra);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String body = created.getBody();
        assertEquals("clinician-a", field(body, "dataEntrySubject"));
        assertFalse(field(body, "createdAt").contains("1999"), body);

        StoredRevision stored = stored(field(body, "id"));
        assertEquals("clinician-a", stored.dataEntrySubject());
        assertTrue(stored.createdAt().toInstant().isAfter(Instant.parse("2020-01-01T00:00:00Z")),
                "the database, not the request body, owns the ingestion instant");
    }
}
