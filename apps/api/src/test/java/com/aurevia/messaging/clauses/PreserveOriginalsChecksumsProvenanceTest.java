package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.messaging.attachments.AttachmentContract.MediatedDownload;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentContract.View;
import com.aurevia.messaging.attachments.AttachmentService;
import com.aurevia.messaging.attachments.AttachmentStore;
import com.aurevia.messaging.attachments.JdbcAttachmentStore;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-03 clause 7 (API evidence): an upload preserves the original payload's
 * metadata and SHA-256 checksum, and every transition leaves an attributable,
 * append-only provenance trail. A wrong tenant, wrong role, stale version or
 * attempted checksum rewrite must be refused without changing that evidence.
 *
 * <p>The green tests exercise the patient attachment path through its domain
 * service and durable PostgreSQL store under forced row-level security. The
 * checksum constant is derived from the synthetic bytes with the same SHA-256
 * and hex pattern as the multipart entry point; the HTTP derivation itself is
 * proved by {@code AttachmentControllerHttpTest}, which this class references
 * but does not drive. Byte-for-byte retention at a private object boundary is
 * not implemented on this path and is documented in the accompanying clause
 * note rather than hidden behind a disabled test.</p>
 *
 * <p>Synthetic fixtures only: two tenants reuse identical local identifiers so
 * any read that escapes its tenant is observable.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_preserve_originals_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PreserveOriginalsChecksumsProvenanceTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String CONNECTION = "conv-preserve";
    static final String UNIT = "unit-preserve";
    static final String PATIENT = "pat-preserve";
    static final String FILE_NAME = "original-consent.pdf";
    static final byte[] PDF = "%PDF-1.4 synthetic original attachment".getBytes(StandardCharsets.UTF_8);
    static final String MAGIC = "25504446";
    static final long SIZE = PDF.length;
    static final String CHECKSUM = sha256(PDF);
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_preserve_originals_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    AttachmentStore attachmentStore;

    @Autowired
    JdbcAttachmentStore store;

    @Autowired
    AttachmentService service;

    /** Create a fresh database, apply all migrations and seed two tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_preserve_originals_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_preserve_originals_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                    + ORG_A + "', 'Tenant A')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                    + ORG_B + "', 'Tenant B')");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_preserve_originals_it WITH (FORCE)");
        }
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "PRESERVE_IT", "preserve-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static ConnectionGrant grant(String org, String connection, String unit, String patient) {
        return new ConnectionGrant(org, connection, unit, patient, true);
    }

    private static UploadRequest upload() {
        return new UploadRequest(CONNECTION, FILE_NAME, "application/pdf", MAGIC, SIZE, CHECKSUM);
    }

    private static Actor patient(String org, String subject, String patientId) {
        return Actor.patient(org, subject, patientId);
    }

    private static Actor staff(String org, String subject, String unit, String role) {
        return Actor.staff(org, subject, unit, role);
    }

    /**
     * Derive the same lowercase SHA-256 hex digest the multipart entry point
     * computes, so the expected checksum is tied to the original bytes.
     *
     * @param bytes original synthetic payload
     * @return lowercase SHA-256 hex digest
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Count the provenance facts of one attachment in a named tenant.
     * Binding the tenant before each count is essential: forced RLS makes an
     * unbound or wrong-tenant count vacuously zero.
     *
     * @param org          tenant whose ledger is counted
     * @param attachmentId owner-scoped attachment identifier
     * @return number of facts visible in that tenant for the attachment
     * @throws Exception when the database cannot be read
     */
    private static int countEventsForAttachment(String org, String attachmentId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.attachment_event WHERE attachment_id = '" + attachmentId + "'")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /**
     * Assert a timestamp survived a persistence round-trip. PostgreSQL stores
     * microseconds while the server clock supplies nanoseconds, so the driver
     * may round by half a microsecond; anything beyond one microsecond means the
     * binding was rewritten.
     *
     * @param expected timestamp produced at upload
     * @param actual   timestamp read back from the store
     */
    private static void assertSameInstant(Instant expected, Instant actual) {
        assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofNanos(1_000)) <= 0,
                "createdAt must survive an unchanged binding");
    }

    /**
     * Select one fact by its type so its actor and tenant can be asserted.
     *
     * @param events ordered fact trail of one attachment
     * @param type   fact type to select
     * @return the single fact of that type
     */
    private static AttachmentEvent factOfType(List<AttachmentEvent> events, EventType type) {
        return events.stream().filter(event -> event.eventType() == type).findFirst().orElseThrow();
    }

    @Test
    void uploadPreservesOriginalChecksumFileNameAndObjectBinding() {
        assertTrue(attachmentStore instanceof JdbcAttachmentStore,
                "the production AttachmentStore bean must be the PostgreSQL adapter");
        as(ORG_A, "patient-preserve", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            Actor uploader = patient(ORG_A, "patient-preserve", PATIENT);
            Attachment uploaded = service.upload(uploader, upload());

            // The original metadata and checksum survive quarantine unchanged.
            assertEquals(CHECKSUM, uploaded.checksum());
            assertEquals(FILE_NAME, uploaded.fileName());
            assertEquals(FileType.PDF, uploaded.fileType());
            assertEquals(SIZE, uploaded.sizeBytes());
            assertEquals(ORG_A, uploaded.organizationId());
            assertEquals("patient-preserve", uploaded.uploaderSubject());
            assertEquals(CONNECTION, uploaded.connectionId());
            assertEquals(UNIT, uploaded.unitId());
            assertEquals(PATIENT, uploaded.patientId());
            assertEquals(1, uploaded.version());
            assertEquals(1, uploaded.objectVersion());
            assertTrue(uploaded.objectKey().startsWith("quarantine/" + ORG_A + "/"));
            assertTrue(uploaded.objectKey().endsWith("/v1"));

            Attachment clean = service.scan(staff(ORG_A, "scanner-preserve", UNIT, "security_scanner"),
                    uploaded.id(), new ScanRequest("clean", null), 1);
            assertEquals(CHECKSUM, clean.checksum());
            assertEquals(AttachmentState.CLEAN, clean.state());

            List<Attachment> published = service.publish(uploader,
                    new PublishRequest(List.of(uploaded.id()), "message-preserve", UNIT));
            assertEquals(1, published.size());
            assertEquals(CHECKSUM, published.get(0).checksum());
            assertEquals(AttachmentState.PUBLISHED, published.get(0).state());

            // Reload from the durable store: the returned DTOs alone would not
            // prove the binding columns survived the lifecycle transitions.
            Attachment persisted = store.attachment(ORG_A, uploaded.id()).orElseThrow();
            assertEquals(CHECKSUM, persisted.checksum());
            assertEquals(FILE_NAME, persisted.fileName());
            assertEquals(FileType.PDF, persisted.fileType());
            assertEquals(SIZE, persisted.sizeBytes());
            assertEquals("patient-preserve", persisted.uploaderSubject());
            assertEquals(CONNECTION, persisted.connectionId());
            assertEquals(UNIT, persisted.unitId());
            assertEquals(PATIENT, persisted.patientId());
            assertEquals(3, persisted.version());
            assertEquals(uploaded.objectKey(), persisted.objectKey());
            assertEquals(1, persisted.objectVersion());
            assertSameInstant(uploaded.createdAt(), persisted.createdAt());
            assertEquals(AttachmentState.PUBLISHED, persisted.state());

            // A mediated handle carries the preserved checksum and object handle only.
            MediatedDownload handle = service.download(uploader, uploaded.id(), 3);
            assertEquals(CHECKSUM, handle.checksum());
            assertEquals(uploaded.objectKey(), handle.objectKey());
            assertEquals(uploaded.objectVersion(), handle.objectVersion());
            assertEquals("application/pdf", handle.contentType());
            assertEquals(SIZE, handle.sizeBytes());

            View view = service.view(uploader, uploaded.id());
            assertEquals(CHECKSUM, view.attachment().checksum());
            List<AttachmentEvent> events = view.events();
            assertEquals(List.of(EventType.UPLOADED, EventType.SCAN_CLEAN,
                    EventType.PUBLISHED, EventType.DOWNLOADED),
                    events.stream().map(AttachmentEvent::eventType).toList());
            assertEquals("patient-preserve", factOfType(events, EventType.UPLOADED).actorSubject());
            assertEquals("scanner-preserve", factOfType(events, EventType.SCAN_CLEAN).actorSubject());
            assertEquals("patient-preserve", factOfType(events, EventType.PUBLISHED).actorSubject());
            assertEquals("patient-preserve", factOfType(events, EventType.DOWNLOADED).actorSubject());
            for (AttachmentEvent event : events) {
                assertEquals(ORG_A, event.organizationId());
                assertEquals(uploaded.id(), event.attachmentId());
            }
            return null;
        });
    }

    @Test
    void jdbcStoreRefusesChecksumRewrite() throws Exception {
        String id = "55555555-5555-5555-5555-555555555555";
        String originalKey = "quarantine/" + ORG_A + "/" + id + "/v1";
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.attachment (organization_id, id, version, uploader_subject, "
                    + "connection_id, unit_id, patient_id, file_name, file_type, size_bytes, checksum, "
                    + "object_key, object_version, verdict, state, created_at) VALUES ('" + ORG_A + "', '" + id
                    + "', 1, 'patient-preserve', '" + CONNECTION + "', '" + UNIT + "', '" + PATIENT
                    + "', '" + FILE_NAME + "', 'application/pdf', " + SIZE + ", '" + CHECKSUM + "', '"
                    + originalKey + "', 1, 'pending', 'quarantined', '2026-11-22T12:00:00Z')");
            try (ResultSet row = statement.executeQuery(
                    "SELECT count(*) FROM public.attachment WHERE id = '" + id + "'")) {
                row.next();
                assertEquals(1, row.getInt(1), "the synthetic row must exist before the rewrite attempt");
            }
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.attachment SET checksum = '" + "b".repeat(64)
                            + "', object_key = 'tampered/v1' WHERE id = '" + id + "'"));
            assertEquals("44000", failure.getSQLState());
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet row = statement.executeQuery(
                    "SELECT checksum, object_key FROM public.attachment WHERE id = '" + id + "'")) {
                assertTrue(row.next());
                assertEquals(CHECKSUM, row.getString("checksum"));
                assertEquals(originalKey, row.getString("object_key"));
            }
        }
    }

    @Test
    void crossTenantDownloadReturnsNotFoundWithoutAppendingAFact() throws Exception {
        as(ORG_A, "seed-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            return null;
        });
        as(ORG_B, "seed-b", () -> {
            store.putGrant(grant(ORG_B, CONNECTION, UNIT, PATIENT));
            return null;
        });
        Attachment owned = as(ORG_A, "patient-a", () -> {
            Attachment uploaded = service.upload(patient(ORG_A, "patient-a", PATIENT), upload());
            service.scan(staff(ORG_A, "scanner-a", UNIT, "security_scanner"),
                    uploaded.id(), new ScanRequest("clean", null), 1);
            service.publish(patient(ORG_A, "patient-a", PATIENT),
                    new PublishRequest(List.of(uploaded.id()), "message-cross", UNIT));
            return uploaded;
        });

        int before = countEventsForAttachment(ORG_A, owned.id());
        assertEquals(3, before, "upload, clean scan and publication must each append a fact");
        ApiException refusal = as(ORG_B, "patient-b", () -> assertThrows(ApiException.class,
                () -> service.download(patient(ORG_B, "patient-b", PATIENT), owned.id(), 3)));
        assertEquals(404, refusal.status());
        assertEquals("NOT_FOUND", refusal.code());
        assertEquals(before, countEventsForAttachment(ORG_A, owned.id()),
                "a refused cross-tenant read appends no provenance fact in the owning tenant");
        assertEquals(0, countEventsForAttachment(ORG_B, owned.id()),
                "a refused cross-tenant read writes no fact into the calling tenant either");
    }

    @Test
    void scanWithStaleVersionIsRefusedLeavingOriginalsUnchanged() {
        as(ORG_A, "patient-stale", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            Attachment uploaded = service.upload(patient(ORG_A, "patient-stale", PATIENT), upload());
            ApiException failure = assertThrows(ApiException.class,
                    () -> service.scan(staff(ORG_A, "scanner-stale", UNIT, "security_scanner"),
                            uploaded.id(), new ScanRequest("clean", null), 9));
            assertEquals(409, failure.status());
            assertEquals("STALE_VERSION", failure.code());
            Attachment stored = store.attachment(ORG_A, uploaded.id()).orElseThrow();
            assertEquals(1, stored.version());
            assertEquals(CHECKSUM, stored.checksum());
            assertEquals(FILE_NAME, stored.fileName());
            assertEquals(uploaded.objectKey(), stored.objectKey());
            assertEquals(1, stored.objectVersion());
            assertSameInstant(uploaded.createdAt(), stored.createdAt());
            assertEquals("patient-stale", stored.uploaderSubject());
            assertEquals(AttachmentState.QUARANTINED, stored.state());
            assertEquals(ScanVerdict.PENDING, stored.verdict());

            // The refusal is itself a provenance fact, but no payload binding changed.
            AttachmentEvent denial = factOfType(
                    service.events(patient(ORG_A, "patient-stale", PATIENT), uploaded.id()),
                    EventType.ACCESS_DENIED);
            assertEquals("conflict", denial.outcome());
            assertEquals("STALE_VERSION", denial.detail());
            assertEquals(uploaded.id(), denial.attachmentId());
            assertEquals("scanner-stale", denial.actorSubject());
            return null;
        });
    }

    @Test
    void wrongRoleCannotPublishLeavingTheFileClean() {
        as(ORG_A, "patient-pub", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            Actor uploader = patient(ORG_A, "patient-pub", PATIENT);
            Attachment uploaded = service.upload(uploader, upload());
            service.scan(staff(ORG_A, "scanner-pub", UNIT, "security_scanner"),
                    uploaded.id(), new ScanRequest("clean", null), 1);
            Actor auditor = staff(ORG_A, "auditor-pub", UNIT, "privacy_auditor");
            ApiException failure = assertThrows(ApiException.class, () -> service.publish(auditor,
                    new PublishRequest(List.of(uploaded.id()), "message-denied", UNIT)));
            assertEquals(403, failure.status());
            assertEquals("FORBIDDEN", failure.code());
            Attachment stored = store.attachment(ORG_A, uploaded.id()).orElseThrow();
            assertEquals(AttachmentState.CLEAN, stored.state());
            assertEquals(CHECKSUM, stored.checksum());
            assertEquals(FILE_NAME, stored.fileName());
            assertEquals(uploaded.objectKey(), stored.objectKey());
            assertEquals(1, stored.objectVersion());
            assertEquals(2, stored.version());
            assertSameInstant(uploaded.createdAt(), stored.createdAt());
            List<AttachmentEvent> trail = service.events(uploader, uploaded.id());
            assertEquals(List.of(EventType.UPLOADED, EventType.SCAN_CLEAN),
                    trail.stream().map(AttachmentEvent::eventType).toList());
            assertTrue(trail.stream().noneMatch(event -> event.eventType() == EventType.PUBLISHED));
            return null;
        });
    }
}
