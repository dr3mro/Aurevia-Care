package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentContract.View;
import com.aurevia.messaging.attachments.AttachmentService;
import com.aurevia.messaging.attachments.AttachmentStore;
import com.aurevia.messaging.attachments.JdbcAttachmentStore;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-07 clause 3 proof: a draft upload stays private to its uploader and can
 * never be rebound to another conversation or uploader.
 *
 * <p>Every value is synthetic. Two tenants use identical local connection, unit
 * and patient identifiers, so a read that escapes tenant scope becomes visible
 * as a wrong answer. The domain service is exercised through its public entry
 * point and the guarded upsert plus V82's forced row-level security and
 * immutable-binding trigger are exercised against a real PostgreSQL database.
 * The patient attachment controller exposes only upload, scan and mediated
 * download, so privacy of a draft's view/events and the non-reuse invariant
 * cannot be observed over HTTP and are proven at the service/store boundary.</p>
 *
 * <p>The uploader may view their own quarantined draft but may not download it;
 * every other subject, unit and tenant is concealed. A rejected rebind must
 * leave all binding columns untouched, including when the writer is the
 * migration owner and therefore subject to the same database trigger.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_draft_uploads_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class DraftUploadsPrivateCannotReusedAcrossConversationsTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String CONNECTION = "conv-shared";
    static final String OTHER_CONNECTION = "conv-other";
    static final String UNIT = "unit-1";
    static final String OTHER_UNIT = "unit-2";
    static final String PATIENT = "pat-shared";
    static final String OTHER_PATIENT = "pat-other";
    static final String CHECKSUM = "a".repeat(64);
    static final String DATABASE = "aurevia_draft_uploads_clause_it";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER aurevia_migrate");
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "CLAUSE_IT", "clause-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Build an active verified connection grant with synthetic identifiers. */
    private static ConnectionGrant grant(String org, String connection, String unit, String patient) {
        return new ConnectionGrant(org, connection, unit, patient, true);
    }

    /** Build a valid PDF upload command with synthetic content metadata. */
    private static UploadRequest upload(String connection) {
        return new UploadRequest(connection, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }

    /** Build a fresh patient actor snapshot. */
    private static Actor patient(String org, String subject, String patientId) {
        return Actor.patient(org, subject, patientId);
    }

    /** Build a fresh staff actor snapshot with a DOWNLOAD-permitting role by default. */
    private static Actor staff(String org, String subject, String unit, String role) {
        return Actor.staff(org, subject, unit, role);
    }

    /** The uploader may view, but not download, their own bound quarantined draft. */
    @Test
    void draftIsPrivateToItsUploaderAndBoundToItsConversation() {
        as(ORG_A, "patient-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            Actor uploader = patient(ORG_A, "patient-a", PATIENT);
            Attachment draft = service.upload(uploader, upload(CONNECTION));

            assertEquals(AttachmentState.QUARANTINED, draft.state());
            assertEquals(ScanVerdict.PENDING, draft.verdict());
            View own = service.view(uploader, draft.id());
            assertEquals(draft.id(), own.attachment().id());
            assertEquals(AttachmentState.QUARANTINED, own.attachment().state());
            assertEquals(CONNECTION, own.attachment().connectionId());
            assertEquals(List.of("uploaded"), own.events().stream()
                    .filter(fact -> draft.id().equals(fact.attachmentId()))
                    .map(fact -> fact.eventType().wire()).toList());

            ApiException notPublished = assertThrows(ApiException.class,
                    () -> service.download(uploader, draft.id(), draft.version()));
            assertEquals(409, notPublished.status());
            assertEquals("NOT_PUBLISHED", notPublished.code());

            Attachment stored = store.attachment(ORG_A, draft.id()).orElseThrow();
            assertEquals(CONNECTION, stored.connectionId());
            assertEquals(UNIT, stored.unitId());
            assertEquals(PATIENT, stored.patientId());
            assertEquals("patient-a", stored.uploaderSubject());
            assertTrue(attachmentStore instanceof JdbcAttachmentStore,
                    "the production AttachmentStore bean must be the PostgreSQL adapter");
            return null;
        });
    }

    /** Another subject, another unit and another tenant cannot see or download the draft. */
    @Test
    void anotherActorCannotSeeOrDownloadTheDraft() {
        Attachment draft = as(ORG_A, "patient-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            return service.upload(patient(ORG_A, "patient-a", PATIENT), upload(CONNECTION));
        });
        String id = draft.id();

        as(ORG_A, "other-patient", () -> {
            Actor other = patient(ORG_A, "other-patient", OTHER_PATIENT);
            ApiException view = assertThrows(ApiException.class, () -> service.view(other, id));
            assertEquals(404, view.status());
            assertEquals("NOT_FOUND", view.code());
            ApiException events = assertThrows(ApiException.class, () -> service.events(other, id));
            assertEquals(404, events.status());
            assertEquals("NOT_FOUND", events.code());
            return null;
        });

        as(ORG_A, "outsider", () -> {
            Actor outsider = staff(ORG_A, "outsider", OTHER_UNIT, "reception");
            ApiException view = assertThrows(ApiException.class, () -> service.view(outsider, id));
            assertEquals(404, view.status());
            ApiException events = assertThrows(ApiException.class, () -> service.events(outsider, id));
            assertEquals(404, events.status());
            ApiException download = assertThrows(ApiException.class,
                    () -> service.download(outsider, id, draft.version()));
            assertEquals(409, download.status());
            assertEquals("NOT_PUBLISHED", download.code());
            assertTrue(service.inbox(outsider, OTHER_UNIT).stream()
                    .noneMatch(item -> id.equals(item.id())));
            return null;
        });

        as(ORG_B, "patient-b", () -> {
            Actor otherTenant = patient(ORG_B, "patient-b", PATIENT);
            ApiException view = assertThrows(ApiException.class, () -> service.view(otherTenant, id));
            assertEquals(404, view.status());
            ApiException events = assertThrows(ApiException.class, () -> service.events(otherTenant, id));
            assertEquals(404, events.status());
            ApiException download = assertThrows(ApiException.class,
                    () -> service.download(otherTenant, id, draft.version()));
            assertEquals(404, download.status());
            assertEquals("NOT_FOUND", download.code());
            return null;
        });

        as(ORG_A, "unit-staff", () -> {
            assertTrue(service.inbox(staff(ORG_A, "unit-staff", UNIT, "reception"), UNIT).stream()
                    .noneMatch(item -> id.equals(item.id())));
            return null;
        });
    }

    /** Neither the store's guarded upsert nor the database trigger allows a rebind. */
    @Test
    void draftCannotBeReboundToAnotherConversationOrUploader() throws Exception {
        Attachment draft = as(ORG_A, "patient-a", () -> {
            store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT));
            store.putGrant(grant(ORG_A, OTHER_CONNECTION, OTHER_UNIT, PATIENT));
            return service.upload(patient(ORG_A, "patient-a", PATIENT), upload(CONNECTION));
        });
        String id = draft.id();

        as(ORG_A, "patient-a", () -> {
            Attachment before = store.attachment(ORG_A, id).orElseThrow();
            Attachment otherConversation = new Attachment(ORG_A, id, before.version(), "patient-a",
                    OTHER_CONNECTION, OTHER_UNIT, PATIENT, before.fileName(), before.fileType(),
                    before.sizeBytes(), before.checksum(), before.objectKey(), before.objectVersion(),
                    before.verdict(), before.state(), before.messageId(), before.recipientUnitId(),
                    before.createdAt());
            ApiException conversation = assertThrows(ApiException.class,
                    () -> store.putAttachment(otherConversation));
            assertEquals(409, conversation.status());
            assertEquals("WRONG_CONVERSATION", conversation.code());

            Attachment otherUploader = new Attachment(ORG_A, id, before.version(), "someone-else",
                    before.connectionId(), before.unitId(), before.patientId(), before.fileName(),
                    before.fileType(), before.sizeBytes(), before.checksum(), before.objectKey(),
                    before.objectVersion(), before.verdict(), before.state(), before.messageId(),
                    before.recipientUnitId(), before.createdAt());
            ApiException uploader = assertThrows(ApiException.class,
                    () -> store.putAttachment(otherUploader));
            assertEquals(409, uploader.status());
            assertEquals("WRONG_CONVERSATION", uploader.code());

            Attachment stored = store.attachment(ORG_A, id).orElseThrow();
            assertEquals(before.uploaderSubject(), stored.uploaderSubject());
            assertEquals(before.connectionId(), stored.connectionId());
            assertEquals(before.unitId(), stored.unitId());
            assertEquals(before.patientId(), stored.patientId());
            assertEquals(before.version(), stored.version());
            assertEquals(before.state(), stored.state());
            assertEquals(before.objectKey(), stored.objectKey());
            assertEquals(before.fileName(), stored.fileName());
            assertEquals(before.fileType(), stored.fileType());
            assertEquals(before.sizeBytes(), stored.sizeBytes());
            assertEquals(before.checksum(), stored.checksum());
            assertEquals(before.createdAt(), stored.createdAt());
            return null;
        });

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.attachment SET connection_id = '" + OTHER_CONNECTION
                            + "' WHERE id = '" + id + "'"));
            assertTrue(failure.getMessage().contains("immutable"));
            try (java.sql.ResultSet result = statement.executeQuery(
                    "SELECT connection_id FROM public.attachment WHERE id = '" + id + "'")) {
                assertTrue(result.next());
                assertEquals(CONNECTION, result.getString("connection_id"));
            }
        }
    }
}
