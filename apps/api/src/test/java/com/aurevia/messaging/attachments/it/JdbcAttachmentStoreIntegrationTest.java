package com.aurevia.messaging.attachments.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.messaging.attachments.AttachmentContract.MediatedDownload;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentService;
import com.aurevia.messaging.attachments.AttachmentStore;
import com.aurevia.messaging.attachments.JdbcAttachmentStore;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-03 / ARC-06 / ARC-07 acceptance for the durable JDBC attachment store.
 *
 * <p>Every value is synthetic. Two tenants use identical local connection,
 * unit, patient and attachment identifiers, so any read that escapes tenant
 * scope is observable. Tenant context is bound exactly as the HTTP interceptor
 * binds it; V82's forced row-level security is the isolation boundary under
 * test, and the domain service is exercised through its public entry point.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_attachment_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class JdbcAttachmentStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String CONNECTION = "conv-shared";
    static final String OTHER_CONNECTION = "conv-other";
    static final String UNIT = "unit-1";
    static final String OTHER_UNIT = "unit-2";
    static final String PATIENT = "pat-shared";
    static final String CHECKSUM = "a".repeat(64);
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_attachment_it";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_attachment_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_attachment_it OWNER aurevia_migrate");
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_attachment_it WITH (FORCE)");
        }
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "ATTACHMENT_IT", "attachment-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static ConnectionGrant grant(String org, String connection, String unit, String patient) {
        return new ConnectionGrant(org, connection, unit, patient, true);
    }

    private static UploadRequest upload(String connection) {
        return new UploadRequest(connection, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }

    private static Actor patient(String org, String subject, String patientId) {
        return Actor.patient(org, subject, patientId);
    }

    private static Actor staff(String org, String subject, String unit, String role) {
        return Actor.staff(org, subject, unit, role);
    }

    @Test
    void productionBeanIsTheJdbcStore() {
        assertNotNull(store);
        assertTrue(attachmentStore instanceof JdbcAttachmentStore,
                "the production AttachmentStore bean must be the PostgreSQL adapter");
    }

    @Test
    void draftIsBoundToItsUploaderAndConversationAndCannotBeReused() {
        as(ORG_A, "patient-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            Attachment uploaded = service.upload(patient(ORG_A, "patient-a", PATIENT), upload(CONNECTION));
            Attachment rebound = new Attachment(uploaded.organizationId(), uploaded.id(), uploaded.version(),
                    uploaded.uploaderSubject(), OTHER_CONNECTION, uploaded.unitId(), uploaded.patientId(),
                    uploaded.fileName(), uploaded.fileType(), uploaded.sizeBytes(), uploaded.checksum(),
                    uploaded.objectKey(), uploaded.objectVersion(), uploaded.verdict(), uploaded.state(),
                    uploaded.messageId(), uploaded.recipientUnitId(), uploaded.createdAt());
            ApiException failure = assertThrows(ApiException.class, () -> store.putAttachment(rebound));
            assertEquals(409, failure.status());
            assertEquals("WRONG_CONVERSATION", failure.code());
            Attachment differentUploader = new Attachment(uploaded.organizationId(), uploaded.id(),
                    uploaded.version(), "someone-else", uploaded.connectionId(), uploaded.unitId(),
                    uploaded.patientId(), uploaded.fileName(), uploaded.fileType(), uploaded.sizeBytes(),
                    uploaded.checksum(), uploaded.objectKey(), uploaded.objectVersion(), uploaded.verdict(),
                    uploaded.state(), uploaded.messageId(), uploaded.recipientUnitId(), uploaded.createdAt());
            ApiException second = assertThrows(ApiException.class, () -> store.putAttachment(differentUploader));
            assertEquals("WRONG_CONVERSATION", second.code());
            assertEquals(CONNECTION, store.attachment(ORG_A, uploaded.id()).orElseThrow().connectionId());
            return null;
        });
    }

    @Test
    void databaseTriggerRejectsRebindingForTheMigrationOwnerToo() throws Exception {
        String id = "33333333-3333-3333-3333-333333333333";
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.attachment (organization_id, id, version, uploader_subject, "
                    + "connection_id, unit_id, patient_id, file_name, file_type, size_bytes, checksum, "
                    + "object_key, object_version, verdict, state, created_at) VALUES ('" + ORG_A + "', '" + id
                    + "', 1, 'patient-a', '" + CONNECTION + "', '" + UNIT + "', '" + PATIENT
                    + "', 'notes.pdf', 'application/pdf', 128, '" + CHECKSUM + "', 'key/v1', 1, "
                    + "'pending', 'quarantined', '2026-11-21T12:00:00Z')");
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.attachment SET connection_id = '" + OTHER_CONNECTION + "' WHERE id = '" + id + "'"));
            assertTrue(failure.getMessage().contains("immutable"));
        }
    }

    @Test
    void factLedgerRejectsMutationForEveryRoleIncludingTheOwner() throws Exception {
        String eventId = "44444444-4444-4444-4444-444444444444";
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.attachment_event (organization_id, id, attachment_id, "
                    + "event_type, actor_subject, outcome, occurred_at) VALUES ('" + ORG_A + "', '" + eventId
                    + "', '" + eventId + "', 'uploaded', 'patient-a', 'success', '2026-11-21T12:00:00Z')");
            SQLException update = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("UPDATE public.attachment_event SET outcome = 'DENIED'"));
            assertTrue(update.getMessage().contains("append-only"));
            SQLException delete = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM public.attachment_event"));
            assertTrue(delete.getMessage().contains("append-only"));
            SQLException truncate = assertThrows(SQLException.class,
                    () -> statement.execute("TRUNCATE public.attachment_event"));
            assertTrue(truncate.getMessage().contains("append-only"));
        }
    }

    @Test
    void unscannedFileIsNotDownloadable() {
        as(ORG_A, "patient-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            Attachment uploaded = service.upload(patient(ORG_A, "patient-a", PATIENT), upload(CONNECTION));
            ApiException quarantined = assertThrows(ApiException.class,
                    () -> service.download(patient(ORG_A, "patient-a", PATIENT), uploaded.id(), 1));
            assertEquals(409, quarantined.status());
            assertEquals("NOT_PUBLISHED", quarantined.code());
            Attachment clean = service.scan(staff(ORG_A, "scanner-a", UNIT, "security_scanner"),
                    uploaded.id(), new ScanRequest("clean", null), 1);
            assertEquals(AttachmentState.CLEAN, clean.state());
            ApiException unpublished = assertThrows(ApiException.class,
                    () -> service.download(patient(ORG_A, "patient-a", PATIENT), uploaded.id(), 2));
            assertEquals("NOT_PUBLISHED", unpublished.code());
            return null;
        });
    }

    @Test
    void otherUnitCannotSeeOrDownloadTheFile() {
        as(ORG_A, "patient-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            Attachment uploaded = service.upload(patient(ORG_A, "patient-a", PATIENT), upload(CONNECTION));
            service.scan(staff(ORG_A, "scanner-a", UNIT, "security_scanner"),
                    uploaded.id(), new ScanRequest("clean", null), 1);
            service.publish(patient(ORG_A, "patient-a", PATIENT),
                    new PublishRequest(List.of(uploaded.id()), "message-1", UNIT));
            Actor outsider = staff(ORG_A, "outsider", OTHER_UNIT, "reception");
            ApiException view = assertThrows(ApiException.class, () -> service.view(outsider, uploaded.id()));
            assertEquals(404, view.status());
            ApiException download = assertThrows(ApiException.class,
                    () -> service.download(outsider, uploaded.id(), 3));
            assertEquals(403, download.status());
            assertEquals("WRONG_UNIT", download.code());
            assertTrue(service.inbox(outsider, OTHER_UNIT).stream()
                    .noneMatch(item -> uploaded.id().equals(item.id())));
            MediatedDownload handle = service.download(patient(ORG_A, "patient-a", PATIENT), uploaded.id(), 3);
            assertEquals(uploaded.checksum(), handle.checksum());
            return null;
        });
    }

    @Test
    void twoTenantsWithIdenticalLocalIdentifiersStayIsolated() {
        as(ORG_A, "seed-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            return null;
        });
        as(ORG_B, "seed-b", () -> {
            store.putGrant(grant(ORG_B, CONNECTION, UNIT, PATIENT));
            return null;
        });
        Attachment a = as(ORG_A, "patient-a", () -> {
            Attachment uploaded = service.upload(patient(ORG_A, "patient-a", PATIENT), upload(CONNECTION));
            service.scan(staff(ORG_A, "scanner-a", UNIT, "security_scanner"),
                    uploaded.id(), new ScanRequest("clean", null), 1);
            service.publish(patient(ORG_A, "patient-a", PATIENT),
                    new PublishRequest(List.of(uploaded.id()), "message-shared", UNIT));
            return uploaded;
        });
        Attachment b = as(ORG_B, "patient-b", () -> {
            Attachment uploaded = service.upload(patient(ORG_B, "patient-b", PATIENT), upload(CONNECTION));
            service.scan(staff(ORG_B, "scanner-b", UNIT, "security_scanner"),
                    uploaded.id(), new ScanRequest("clean", null), 1);
            service.publish(patient(ORG_B, "patient-b", PATIENT),
                    new PublishRequest(List.of(uploaded.id()), "message-shared", UNIT));
            return uploaded;
        });

        assertEquals(ORG_A, a.organizationId());
        assertEquals(ORG_B, b.organizationId());
        assertEquals(CONNECTION, b.connectionId());
        assertEquals(a.fileType(), b.fileType());
        assertTrue(as(ORG_A, "patient-a", () -> store.attachments(ORG_A).stream()
                .allMatch(item -> ORG_A.equals(item.organizationId()))));
        assertTrue(as(ORG_B, "patient-b", () -> store.attachments(ORG_B).stream()
                .allMatch(item -> ORG_B.equals(item.organizationId()))));

        ApiException crossTenantRead = as(ORG_B, "patient-b",
                () -> assertThrows(ApiException.class,
                        () -> service.view(patient(ORG_B, "patient-b", PATIENT), a.id())));
        assertEquals(404, crossTenantRead.status());
        ApiException crossTenantDownload = as(ORG_B, "patient-b",
                () -> assertThrows(ApiException.class,
                        () -> service.download(patient(ORG_B, "patient-b", PATIENT), a.id(), 3)));
        assertEquals(404, crossTenantDownload.status());

        Instant at = Instant.parse("2026-11-21T12:00:00Z");
        as(ORG_A, "patient-a", () -> {
            Attachment foreign = new Attachment(ORG_B, a.id(), 1, "patient-b", CONNECTION, UNIT, PATIENT,
                    "notes.pdf", FileType.PDF, 128, CHECKSUM, "foreign/v1", 1,
                    com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict.PENDING,
                    AttachmentState.QUARANTINED, null, null, at);
            assertThrows(Exception.class, () -> store.putAttachment(foreign),
                    "WITH CHECK rejects a row whose organization_id is not the bound tenant");
            return null;
        });
    }
}
