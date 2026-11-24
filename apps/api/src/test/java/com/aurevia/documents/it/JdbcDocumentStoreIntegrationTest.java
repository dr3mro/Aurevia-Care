package com.aurevia.documents.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.ArtifactState;
import com.aurevia.documents.DocumentContract.CompleteRequest;
import com.aurevia.documents.DocumentContract.DocumentAuthorization;
import com.aurevia.documents.DocumentContract.ScanRequest;
import com.aurevia.documents.DocumentContract.UploadRequest;
import com.aurevia.documents.DocumentGuard;
import com.aurevia.documents.DocumentModel.AssociationEvent;
import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentRepository;
import com.aurevia.documents.DocumentStore;
import com.aurevia.documents.JdbcDocumentStore;
import com.aurevia.documents.PdfBindingService;
import com.aurevia.documents.QuarantineService;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-06 / CLN-07 acceptance for the durable document store.
 *
 * <p>Every value is synthetic. Two tenants use identical local context and
 * patient identifiers so a read that escapes tenant scope is observable. The
 * domain services are exercised through their public entry points with the
 * production JDBC beans; V81's forced row-level security is the isolation
 * boundary under test. Object bytes never enter the database.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_document_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class JdbcDocumentStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String CONTEXT = "ctx-shared";
    static final String PATIENT = "pat-shared";
    static final String CHECKSUM = "a".repeat(64);
    static final String OTHER_CHECKSUM = "b".repeat(64);
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_document_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DocumentStore documentStore;

    @Autowired
    JdbcDocumentStore jdbcStore;

    @Autowired
    DocumentGuard guard;

    @Autowired
    QuarantineService quarantine;

    @Autowired
    PdfBindingService pdf;

    @Autowired
    DocumentRepository repository;

    @Autowired
    TransactionTemplate transactions;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_document_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_document_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : List.of(ORG_A, ORG_B)) {
                statement.execute("SET app.current_org = '" + org + "'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                        + org + "', 'Document tenant')");
                statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                        + "VALUES ('" + org + "', 'clinician-a', 'Clinician A')");
            }
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_document_it WITH (FORCE)");
        }
    }

    /** Bind tenant context and a transaction exactly as the HTTP layer does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "DOCUMENT_IT", "documents"));
        try {
            return transactions.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    private static Actor patient(String org, String subject) {
        return Actor.patient(org, subject, PATIENT);
    }

    private static Actor staff(String org, String subject, String role) {
        return Actor.staff(org, subject, role);
    }

    private static UploadRequest pdfUpload() {
        return new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }

    @Test
    void productionBeansUseTheJdbcStore() {
        assertNotNull(guard);
        assertNotNull(quarantine);
        assertNotNull(pdf);
        assertNotNull(jdbcStore);
        assertTrue(documentStore instanceof JdbcDocumentStore,
                "the production DocumentStore bean must be the PostgreSQL adapter");
    }

    @Test
    void quarantinedArtifactIsNeverReturnedAndNoBytesEnterTheDatabase() throws Exception {
        as(ORG_A, "patient-a", () -> {
            documentStore.putAuthorization(new DocumentAuthorization(ORG_A, CONTEXT, PATIENT, true));
            Artifact uploaded = quarantine.upload(patient(ORG_A, "patient-a"), pdfUpload());
            assertEquals(ArtifactState.QUARANTINED, uploaded.state());
            ApiException quarantined = assertThrows(ApiException.class,
                    () -> quarantine.download(patient(ORG_A, "patient-a"), uploaded.id(), 1));
            assertEquals(409, quarantined.status());
            assertEquals("NOT_COMPLETED", quarantined.code());
            quarantine.scan(staff(ORG_A, "scanner-a", "security_scanner"), uploaded.id(),
                    new ScanRequest("failed", "threat"), 1);
            ApiException failed = assertThrows(ApiException.class,
                    () -> quarantine.download(patient(ORG_A, "patient-a"), uploaded.id(), 2));
            assertEquals("NOT_COMPLETED", failed.code());
            return null;
        });
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT column_name FROM information_schema.columns
                      WHERE table_schema = 'public' AND table_name = 'document_artifact'
                        AND data_type = 'bytea'
                     """)) {
            assertFalse(rows.next(), "document artifacts must hold metadata only, never object bytes");
        }
    }

    @Test
    void checksumMismatchIsRejectedBeforeCompletion() {
        as(ORG_A, "patient-a", () -> {
            documentStore.putAuthorization(new DocumentAuthorization(ORG_A, CONTEXT, PATIENT, true));
            Artifact uploaded = quarantine.upload(patient(ORG_A, "patient-a"), pdfUpload());
            quarantine.scan(staff(ORG_A, "scanner-a", "security_scanner"), uploaded.id(),
                    new ScanRequest("clean", null), 1);
            documentStore.putObject(ORG_A, uploaded.objectKey(), 1, OTHER_CHECKSUM, 128L);
            assertFalse(documentStore.verifyObject(ORG_A, uploaded.objectKey(), 1, uploaded.checksum()));
            ApiException mismatch = assertThrows(ApiException.class,
                    () -> quarantine.complete(staff(ORG_A, "reviewer-a", "document_reviewer"),
                            uploaded.id(), new CompleteRequest("ok"), 2));
            assertEquals(409, mismatch.status());
            assertEquals("OBJECT_UNVERIFIED", mismatch.code());
            assertEquals(ArtifactState.CLEAN,
                    documentStore.artifact(ORG_A, uploaded.id()).orElseThrow().state());
            return null;
        });
    }

    @Test
    void voidingAnAssociationKeepsItsOriginalAndItsHistory() throws Exception {
        String subjectId = "44444444-4444-4444-4444-444444444444";
        String[] associationId = new String[1];
        byte[] original = "%PDF-1.4 synthetic original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             java.sql.PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO public.document_association
                         (organization_id, subject_type, subject_id, file_name, media_type, content)
                     VALUES (public.app_org(), 'chart', CAST(? AS uuid), 'scan.pdf', 'application/pdf', ?)
                     RETURNING id::text
                     """)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.current_org = '" + ORG_A + "'");
                statement.execute("SET app.actor = 'clinician-a'");
                statement.execute("SET app.purpose = 'DOCUMENT_READ'");
            }
            insert.setString(1, subjectId);
            insert.setBytes(2, original);
            try (ResultSet rows = insert.executeQuery()) {
                assertTrue(rows.next());
                associationId[0] = rows.getString(1);
            }
        }
        String id = associationId[0];
        as(ORG_A, "clinician-a", () -> {
            DocumentAssoc current = repository.findAssociation(id).orElseThrow();
            assertEquals("active", current.status());
            assertEquals(1, repository.voidAssociation(id, current.version(), "wrong patient"));
            repository.appendEvent(id, "void", "wrong patient");
            DocumentAssoc voided = repository.findAssociation(id).orElseThrow();
            assertEquals("voided", voided.status());
            assertEquals("wrong patient", voided.voidReason());
            List<AssociationEvent> history = repository.associationHistory(id);
            assertEquals(1, history.size());
            assertEquals("void", history.get(0).action());
            assertEquals("wrong patient", history.get(0).reason());
            assertNotNull(repository.verifiedAssociationContent(id).orElseThrow().content());
            return null;
        });
    }

    @Test
    void twoTenantsWithIdenticalLocalIdentifiersStayIsolated() {
        as(ORG_A, "seed-a", () -> {
            documentStore.putAuthorization(new DocumentAuthorization(ORG_A, CONTEXT, PATIENT, true));
            return null;
        });
        as(ORG_B, "seed-b", () -> {
            documentStore.putAuthorization(new DocumentAuthorization(ORG_B, CONTEXT, PATIENT, true));
            return null;
        });
        Artifact a = as(ORG_A, "patient-a", () -> {
            Artifact uploaded = quarantine.upload(patient(ORG_A, "patient-a"), pdfUpload());
            quarantine.scan(staff(ORG_A, "scanner-a", "security_scanner"), uploaded.id(),
                    new ScanRequest("clean", null), 1);
            return uploaded;
        });
        Artifact b = as(ORG_B, "patient-b", () -> {
            Artifact uploaded = quarantine.upload(patient(ORG_B, "patient-b"), pdfUpload());
            quarantine.scan(staff(ORG_B, "scanner-b", "security_scanner"), uploaded.id(),
                    new ScanRequest("clean", null), 1);
            return uploaded;
        });
        assertEquals(ORG_A, a.organizationId());
        assertEquals(ORG_B, b.organizationId());
        assertTrue(as(ORG_A, "patient-a", () -> documentStore.artifacts(ORG_A).stream()
                .allMatch(item -> ORG_A.equals(item.organizationId()))));
        assertTrue(as(ORG_B, "patient-b", () -> documentStore.artifacts(ORG_B).stream()
                .allMatch(item -> ORG_B.equals(item.organizationId()))));
        ApiException crossTenant = as(ORG_B, "patient-b", () -> assertThrows(ApiException.class,
                () -> quarantine.download(patient(ORG_B, "patient-b"), a.id(), 2)));
        assertEquals(404, crossTenant.status());

        as(ORG_A, "seed-a", () -> {
            documentStore.putAuthorizationFor(ORG_A, CONTEXT,
                    new DocumentAuthorization(ORG_B, CONTEXT, PATIENT, true));
            ApiException foreign = assertThrows(ApiException.class,
                    () -> quarantine.upload(patient(ORG_A, "patient-a"), pdfUpload()));
            assertEquals("WRONG_TENANT", foreign.code());
            documentStore.putAuthorization(new DocumentAuthorization(ORG_A, CONTEXT, PATIENT, true));
            return null;
        });
    }

    @Test
    void immutableLedgersRejectMutationForTheMigrationOwner() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("""
                    INSERT INTO public.document_event
                        (organization_id, id, resource_id, event_type, actor_subject, outcome, occurred_at)
                    VALUES ('%s', '55555555-5555-5555-5555-555555555555', '%s',
                            'uploaded', 'patient-a', 'success', '2026-11-21T12:00:00Z')
                    """.formatted(ORG_A, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
            SQLException update = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("UPDATE public.document_event SET outcome = 'DENIED'"));
            assertTrue(update.getMessage().contains("append-only"));
            SQLException delete = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM public.document_event"));
            assertTrue(delete.getMessage().contains("append-only"));
            SQLException truncate = assertThrows(SQLException.class,
                    () -> statement.execute("TRUNCATE public.document_event"));
            assertTrue(truncate.getMessage().contains("append-only"));
        }
    }
}
