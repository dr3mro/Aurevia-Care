package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aurevia.documents.DocumentModel.VerifiedContent;

/**
 * Real-PostgreSQL acceptance for the V75 verified-download function (CLN-07).
 *
 * <p>The digest is recomputed from the exact bytes retained in the association.
 * A tampered original is flagged, a void keeps the original bytes, and forced
 * RLS conceals another tenant's association through this read path. All data is
 * synthetic; the test creates and drops its own database.</p>
 */
@Tag("integration")
class DocumentDigestIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_doc_digest_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TAMPERED = "%PDF-1.4 TAMPERED bytes!!".getBytes(StandardCharsets.UTF_8);

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_doc_digest_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_doc_digest_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a");
            seed(statement, ORG_B, "clinician-b");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_doc_digest_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject) throws SQLException {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + subject + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', '" + subject + "')");
    }

    private static Connection appConnection(String org, String subject) throws SQLException {
        Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = '" + subject + "'");
            statement.execute("SET app.purpose = 'DOCUMENT_READ'");
        }
        return connection;
    }

    private static String linkOriginal() throws SQLException {
        try (Connection connection = appConnection(ORG_A, "clinician-a");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO public.document_association
                         (organization_id, subject_type, subject_id, file_name, media_type, content)
                     VALUES (public.app_org(), 'chart', CAST(? AS uuid), 'scan.pdf', 'application/pdf', ?)
                     RETURNING id::text
                     """)) {
            insert.setString(1, "44444444-4444-4444-4444-444444444444");
            insert.setBytes(2, ORIGINAL);
            try (ResultSet resultSet = insert.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static Optional<VerifiedContent> verifiedContent(String id, String org, String subject)
            throws SQLException {
        try (Connection connection = appConnection(org, subject);
             PreparedStatement query = connection.prepareStatement("""
                     SELECT content, intact
                       FROM public.document_association_verified_content(CAST(? AS uuid))
                     """)) {
            query.setString(1, id);
            try (ResultSet resultSet = query.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VerifiedContent(resultSet.getBytes("content"),
                        resultSet.getBoolean("intact")));
            }
        }
    }

    private static void corruptBytes(String id) throws SQLException {
        // A superuser simulates storage-layer corruption with triggers disabled.
        try (Connection root = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try (PreparedStatement update = root.prepareStatement(
                    "UPDATE public.document_association SET content = ? WHERE id = CAST(? AS uuid)")) {
                update.setBytes(1, TAMPERED);
                update.setString(2, id);
                update.executeUpdate();
            }
            statement.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void retainedBytesVerifyThenTamperingIsFlagged() throws Exception {
        String id = linkOriginal();
        VerifiedContent intact = verifiedContent(id, ORG_A, "clinician-a").orElseThrow();
        assertArrayEquals(ORIGINAL, intact.content());
        assertTrue(intact.intact());

        corruptBytes(id);
        VerifiedContent tampered = verifiedContent(id, ORG_A, "clinician-a").orElseThrow();
        assertArrayEquals(TAMPERED, tampered.content());
        assertFalse(tampered.intact(), "retained bytes must be flagged, not raised as a SQL error");
    }

    @Test
    void voidKeepsTheOriginalAndAnotherTenantCannotReadIt() throws Exception {
        String id = linkOriginal();
        // The guarded update keeps identity, bytes and digest and records a reason.
        try (Connection connection = appConnection(ORG_A, "clinician-a");
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE public.document_association
                        SET status = 'voided', void_reason = 'wrong chart', version = version + 1
                      WHERE id = CAST(? AS uuid) AND status = 'active'
                     """)) {
            update.setString(1, id);
            assertEquals(1, update.executeUpdate());
        }
        VerifiedContent kept = verifiedContent(id, ORG_A, "clinician-a").orElseThrow();
        assertArrayEquals(ORIGINAL, kept.content());
        assertTrue(kept.intact());
        assertTrue(verifiedContent(id, ORG_B, "clinician-b").isEmpty());
    }
}
