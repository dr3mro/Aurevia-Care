package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentService;
import com.aurevia.smartclinic.messaging.attachments.AttachmentStore;
import com.aurevia.smartclinic.messaging.attachments.InMemoryAttachmentStore;

/**
 * MSG-03 clause 8 evidence: a file a patient sends to a care unit enters that
 * unit's inbox as patient-supplied material.
 *
 * <p>The enabled tests exercise the domain service over the synthetic in-memory
 * store: a patient upload becomes a clean draft, explicit publication binds it
 * to a message and recipient unit, and the unit inbox contains the published
 * file with its uploader, patient and message provenance intact. The negative
 * tests show that unpublished files, the wrong unit, patients and other tenants
 * are refused or see nothing. All identifiers and payload metadata are
 * synthetic; no payload bytes, patient data or credentials are used.</p>
 *
 * <p>The HTTP route that would prove the whole chain is not built yet. It is
 * kept as a complete, disabled test in {@code SentFileAppearsInUnitConversationInboxOverHttpTest}
 * so a later task can enable it once publish/inbox routes and the attachment to
 * conversation-message link exist.</p>
 */
class FileSentUnitEntersConversationInboxPatientTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "unit-1";
    private static final String OTHER_UNIT = "unit-2";
    private static final String PATIENT = "pat-001";
    private static final String CONNECTION = "conv-1";
    private static final String OTHER_CONNECTION = "conv-2";
    private static final String MESSAGE = "msg-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private AttachmentStore store;
    private AttachmentService service;

    private final Actor patientA = Actor.patient(ORG_A, "patient-a", PATIENT);
    private final Actor scannerA = Actor.staff(ORG_A, "scanner-a", UNIT, "security_scanner");
    private final Actor staffA = Actor.staff(ORG_A, "staff-a", UNIT, "reception");
    private final Actor wrongUnitStaffA = Actor.staff(ORG_A, "other-staff", OTHER_UNIT, "reception");
    private final Actor patientB = Actor.patient(ORG_B, "patient-b", PATIENT);
    private final Actor staffB = Actor.staff(ORG_B, "staff-b", UNIT, "reception");

    /**
     * Reset the synthetic store on a fixed clock and install verified grants for
     * both tenants so tenant isolation is exercised with identical local ids.
     */
    @BeforeEach
    void setUp() {
        store = new InMemoryAttachmentStore();
        service = new AttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT, true));
        store.putGrant(grant(ORG_A, OTHER_CONNECTION, OTHER_UNIT, PATIENT, true));
        store.putGrant(grant(ORG_B, CONNECTION, UNIT, PATIENT, true));
    }

    @Test
    void publishedPatientFileAppearsInTheReceivingUnitInboxAsPatientSuppliedMaterial() {
        Attachment uploaded = service.upload(patientA, pdf(CONNECTION));
        Attachment clean = clean(uploaded);
        Attachment published = service.publish(patientA,
                new PublishRequest(List.of(clean.id()), MESSAGE, UNIT)).get(0);

        // The published projection keeps the grant-derived patient, the uploader
        // subject and the explicit message/recipient binding as separate values.
        assertEquals(AttachmentState.PUBLISHED, published.state());
        assertEquals(PATIENT, published.patientId());
        assertEquals("patient-a", published.uploaderSubject());
        assertEquals(MESSAGE, published.messageId());
        assertEquals(UNIT, published.recipientUnitId());
        assertEquals(UNIT, published.unitId());
        assertEquals(CONNECTION, published.connectionId());
        assertEquals(ORG_A, published.organizationId());

        // Those same values survive into the unit inbox projection.
        List<Attachment> inbox = service.inbox(staffA, UNIT);
        assertEquals(1, inbox.size());
        Attachment item = inbox.get(0);
        assertEquals(published.id(), item.id());
        assertEquals(AttachmentState.PUBLISHED, item.state());
        assertEquals(PATIENT, item.patientId());
        assertEquals("patient-a", item.uploaderSubject());
        assertEquals(MESSAGE, item.messageId());
        assertEquals(UNIT, item.recipientUnitId());
        assertEquals(UNIT, item.unitId());

        // Publication is its own append-only fact, with the patient actor as source.
        List<AttachmentEvent> trail = service.events(staffA, clean.id());
        AttachmentEvent publishedFact = trail.stream()
                .filter(fact -> fact.eventType() == EventType.PUBLISHED)
                .findFirst().orElseThrow();
        assertEquals(clean.id(), publishedFact.attachmentId());
        assertEquals("patient-a", publishedFact.actorSubject());
        assertEquals("success", publishedFact.outcome());
        assertEquals(ORG_A, publishedFact.organizationId());
        assertEquals(NOW, publishedFact.occurredAt());
    }

    @Test
    void unpublishedFileStaysOutOfTheUnitInbox() {
        // Quarantined, clean-but-unpublished and failed files are all held back:
        // only state == PUBLISHED passes the unit inbox filter.
        Attachment quarantined = service.upload(patientA, pdf(CONNECTION));
        Attachment scannedClean = clean(service.upload(patientA, pdf(CONNECTION)));
        Attachment failed = service.scan(scannerA, scannedClean.id(),
                new ScanRequest("failed", "signature"), scannedClean.version());
        // A fourth file stays CLEAN and unpublished: it must also be held back.
        Attachment cleanDraft = clean(service.upload(patientA, pdf(CONNECTION)));
        assertTrue(service.inbox(staffA, UNIT).isEmpty());
        assertEquals(AttachmentState.QUARANTINED, quarantined.state());
        assertEquals(AttachmentState.FAILED, failed.state());
        assertEquals(AttachmentState.CLEAN, cleanDraft.state());
    }

    @Test
    void staffFromAnotherUnitCannotReadTheInbox() {
        Attachment published = publishFileTo(patientA, OTHER_CONNECTION, OTHER_UNIT, "msg-2");

        // A genuinely published file for another unit is visible in that unit only.
        assertFalse(service.inbox(staffA, UNIT).stream()
                .anyMatch(item -> published.id().equals(item.id())));
        Actor otherUnitStaff = Actor.staff(ORG_A, "other-staff", OTHER_UNIT, "reception");
        assertTrue(service.inbox(otherUnitStaff, OTHER_UNIT).stream()
                .anyMatch(item -> published.id().equals(item.id())));

        // A staff actor whose unit differs from the requested inbox, or who has no
        // unit assignment at all, is refused.
        assertDenied(403, "WRONG_UNIT", () -> service.inbox(wrongUnitStaffA, UNIT));
        assertDenied(403, "WRONG_UNIT", () -> service.inbox(
                new Actor(ORG_A, "staff-a", Set.of("reception"), null, null), OTHER_UNIT));
    }

    @Test
    void patientCannotReadTheUnitInbox() {
        publishFileTo(patientA, CONNECTION, UNIT, MESSAGE);
        assertDenied(403, "PATIENT_INBOX_UNAVAILABLE", () -> service.inbox(patientA, UNIT));
        // The patient's own message list is a different surface; it must not be
        // satisfied by the unit file inbox.
        assertTrue(service.inbox(staffA, UNIT).size() == 1);
    }

    @Test
    void otherTenantCannotSeeTheUnitInbox() {
        Attachment inA = publishFileTo(patientA, CONNECTION, UNIT, "msg-a");
        Attachment inB = publishFileTo(patientB, CONNECTION, UNIT, "msg-b");

        assertEquals(ORG_A, inA.organizationId());
        assertEquals(ORG_B, inB.organizationId());
        assertFalse(inA.id().equals(inB.id()));
        assertTrue(service.inbox(staffA, UNIT).stream().anyMatch(item -> inA.id().equals(item.id())));
        assertTrue(service.inbox(staffA, UNIT).stream().noneMatch(item -> inB.id().equals(item.id())));
        assertTrue(service.inbox(staffB, UNIT).stream().anyMatch(item -> inB.id().equals(item.id())));
        assertTrue(service.inbox(staffB, UNIT).stream().noneMatch(item -> inA.id().equals(item.id())));
    }

    // --- helpers -------------------------------------------------------------

    private Attachment publishFileTo(Actor uploader, String connection, String unit, String message) {
        Attachment uploaded = service.upload(uploader, pdf(connection));
        // The scanner acts in the uploader's tenant: a cross-tenant scanner must
        // not be able to see the draft, so derive it from the uploader's org.
        Actor scanner = Actor.staff(uploader.organizationId(), "scanner-" + uploader.organizationId(),
                unit, "security_scanner");
        Attachment clean = service.scan(scanner, uploaded.id(),
                new ScanRequest("clean", null), uploaded.version());
        return service.publish(uploader,
                new PublishRequest(List.of(clean.id()), message, unit)).get(0);
    }

    private Attachment clean(Attachment uploaded) {
        return service.scan(scannerA, uploaded.id(), new ScanRequest("clean", null), uploaded.version());
    }

    private static UploadRequest pdf(String connection) {
        return new UploadRequest(connection, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }

    private static ConnectionGrant grant(String org, String connection, String unit, String patient,
                                         boolean active) {
        return new ConnectionGrant(org, connection, unit, patient, active);
    }

    private static void assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }
}

/**
 * End-to-end HTTP proof of MSG-03 clause 8: a patient file is published and then
 * read from the receiving unit's conversation inbox over HTTP.
 *
 * <p>This class is complete but disabled. A later task must add a publish route
 * and a recipient-unit inbox route to
 * {@code com.aurevia.smartclinic.messaging.attachments.AttachmentController},
 * link the published {@code messageId} to a conversation message marked as
 * patient-supplied, and document both routes in the OpenAPI contract; then the
 * test annotation can be removed and the fixture below enabled.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_file_inbox_it")
@ActiveProfiles("synthetic-auth")
class SentFileAppearsInUnitConversationInboxOverHttpTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "44444444-4444-4444-4444-444444444444";
    private static final String FILE_ID = "33333333-3333-3333-3333-333333333333";
    private static final String UNIT = "unit-1";
    private static final String CONNECTION = "conv-http-1";
    private static final String MESSAGE = "msg-http-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_file_inbox_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Create the isolated database, apply production migrations and seed synthetic actors. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_file_inbox_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_file_inbox_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, "patient-http", "patient");
            seedUser(statement, "reception-http", "reception");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'patient-http'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES (CAST('" + ORG_A + "' AS uuid), CAST('" + PATIENT + "' AS uuid), 1, "
                    + "'MRN-FILE-INBOX', 'CLINIC', 'Synthetic File Patient', 'synthetic file patient', "
                    + "DATE '1990-05-20')");
            statement.execute("INSERT INTO public.attachment_connection_grant "
                    + "(organization_id, connection_id, unit_id, patient_id, active) VALUES ("
                    + "CAST('" + ORG_A + "' AS uuid), '" + CONNECTION + "', '" + UNIT + "', '"
                    + PATIENT + "', true)");
            // A clean draft in quarantine: the upload and clean-scan steps already
            // have their own HTTP coverage; publish + inbox are the missing links.
            statement.execute("INSERT INTO public.attachment "
                    + "(organization_id, id, version, uploader_subject, connection_id, unit_id, patient_id, "
                    + "file_name, file_type, size_bytes, checksum, object_key, object_version, verdict, "
                    + "state, message_id, recipient_unit_id, created_at) VALUES ("
                    + "CAST('" + ORG_A + "' AS uuid), CAST('" + FILE_ID + "' AS uuid), 2, 'patient-http', "
                    + "'" + CONNECTION + "', '" + UNIT + "', '" + PATIENT + "', 'notes.pdf', "
                    + "'application/pdf', 128, '" + CHECKSUM + "', 'quarantine/" + ORG_A + "/" + FILE_ID
                    + "/v1', 1, 'clean', 'clean', null, null, now())");
        }
    }

    /** Drop the isolated fixture database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_file_inbox_it WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the randomly assigned server port. */
    @BeforeEach
    void initRest(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedUser(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT CAST('" + ORG_A + "' AS uuid), 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization "
                + "WHERE organization_id = CAST('" + ORG_A + "' AS uuid))");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES (CAST('" + ORG_A + "' AS uuid), '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT CAST('" + ORG_A + "' AS uuid), id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = CAST('" + ORG_A + "' AS uuid)");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT CAST('" + ORG_A + "' AS uuid), m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = CAST('" + ORG_A + "' AS uuid)");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", ORG_A);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /**
     * Complete HTTP acceptance for the clause; disabled until the publish and
     * unit-inbox routes and the attachment-to-conversation-message link exist.
     */
    @Test
    @Disabled("GAP: AttachmentController exposes only upload/scan/download; no publish/inbox route and "
            + "no attachment-to-conversation-message link or patient-supplied marker; no OpenAPI paths")
    void sentFileAppearsInUnitConversationInboxOverHttp() {
        String publishBody = "{\"attachmentIds\":[\"" + FILE_ID + "\"],\"messageId\":\"" + MESSAGE
                + "\",\"recipientUnitId\":\"" + UNIT + "\"}";
        ResponseEntity<String> published = request(HttpMethod.POST, "/api/v1/patient/files/publish",
                publishBody, "patient-http");
        assertEquals(HttpStatus.OK, published.getStatusCode(), published.getBody());

        ResponseEntity<String> inbox = request(HttpMethod.GET,
                "/api/v1/patient/files/inbox?unitId=" + UNIT, null, "reception-http");
        assertEquals(HttpStatus.OK, inbox.getStatusCode(), inbox.getBody());
        assertTrue(inbox.getBody().contains("\"" + FILE_ID + "\""));
        assertTrue(inbox.getBody().contains("\"patientId\":\"" + PATIENT + "\""));
        assertTrue(inbox.getBody().contains("\"uploaderSubject\":\"patient-http\""));
        assertTrue(inbox.getBody().contains("\"messageId\":\"" + MESSAGE + "\""));
        assertTrue(inbox.getBody().contains("\"recipientUnitId\":\"" + UNIT + "\""));
        assertTrue(inbox.getBody().contains("\"state\":\"published\""));
        assertTrue(inbox.getBody().contains("\"eventType\":\"published\""));
    }
}
