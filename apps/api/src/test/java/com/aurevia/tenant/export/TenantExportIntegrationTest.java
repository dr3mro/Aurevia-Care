package com.aurevia.tenant.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.tenant.TenantContext;
import com.aurevia.tenant.export.ExportModel.AuditEntry;
import com.aurevia.tenant.export.ExportModel.ExportJob;
import com.aurevia.tenant.export.ExportModel.ExportSource;

/** OPS-07 database gate: forced RLS keeps another tenant's export rows invisible. */
@Tag("integration")
class TenantExportIntegrationTest {

    private static final String ORG_A = "61111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "62222222-2222-2222-2222-222222222222";
    private static final String DB = "orevia_export_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", "postgres");

    private static Connection connection;
    private static SingleConnectionDataSource datasource;
    private static JdbcTemplate jdbc;
    private static JdbcExportStore store;

    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER orevia_migrate");
        }
        Flyway.configure().dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        connection = DriverManager.getConnection(URL, "orevia_migrate", "");
        datasource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(datasource);
        store = new JdbcExportStore(new NamedParameterJdbcTemplate(datasource));
        seed();
        useTenant(ORG_A);
        TenantContext.set(new TenantContext(ORG_A, "admin", "EXPORT", "corr"));
    }

    @AfterAll
    static void cleanUp() throws Exception {
        TenantContext.clear();
        if (connection != null) {
            connection.close();
        }
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void useTenant(String org) {
        jdbc.execute("SET app.current_org = '" + org + "'");
        jdbc.execute("SET app.actor = 'admin'");
    }

    private static void seed() throws Exception {
        try (Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "11111111-1111-1111-1111-111111111111", "doc-a");
            seedTenant(statement, ORG_B, "22222222-2222-2222-2222-222222222222", "doc-b");
        }
    }

    private static void seedTenant(Statement statement, String org, String id, String key)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES "
                + "('" + org + "', 'Export " + key + "')");
        document(statement, org, id, key);
        statement.execute("INSERT INTO public.audit_event (organization_id, action, resource_type, "
                + "resource_id, purpose, scope, correlation_id, outcome) VALUES "
                + "('" + org + "', 'released', 'documents', '" + id + "', "
                + "'EXPORT', 'EXPORT', 'corr', 'SUCCESS')");
    }

    private static void document(Statement statement, String org, String id, String key)
            throws Exception {
        statement.execute("INSERT INTO public.document_artifact (organization_id, id, version, "
                + "uploader_subject, context_id, patient_id, file_name, file_type, size_bytes, checksum, "
                + "object_key, object_version, verdict, state, created_at) VALUES ('" + org + "', '" + id
                + "', 1, 'admin', 'ctx-" + key + "', 'patient', 'scan.pdf', 'application/pdf', 4, '"
                + "a".repeat(64) + "', '" + key + "', 1, 'clean', 'completed', now())");
        statement.execute("INSERT INTO public.document_release (organization_id, revision_id, "
                + "revision_version, content_checksum, policy_version, released_by, released_at) VALUES "
                + "('" + org + "', '" + id + "', 1, '" + "a".repeat(64) + "', '"
                + TenantExportService.POLICY_VERSION + "', 'admin', now())");
    }

    @Test
    void authorizedSourcesContainOnlyTheActiveTenant() {
        List<ExportSource> sources = store.authorizedSources(TenantExportService.POLICY_VERSION);
        assertEquals(1, sources.size());
        assertEquals("11111111-1111-1111-1111-111111111111", sources.get(0).recordId());
        assertEquals("doc-a", sources.get(0).objectId());
        assertEquals("release_policy", sources.get(0).authorization());
    }

    @Test
    void auditHistoryIsTenantScoped() {
        List<AuditEntry> entries = store.auditFor("11111111-1111-1111-1111-111111111111");
        assertEquals(1, entries.size());
        assertEquals("released", entries.get(0).action());
        assertTrue(store.auditFor("22222222-2222-2222-2222-222222222222").isEmpty());
    }

    @Test
    void aJobFromAnotherTenantIsInvisibleAndExpiryIsDurable() {
        ExportJob job = new ExportJob("71111111-1111-1111-1111-111111111111", "COMPLETE",
                "export-a", 1, "b".repeat(64),
                "2026-11-21T10:00:00Z", "2026-11-22T10:00:00Z");
        assertTrue(store.save(job));
        useTenant(ORG_B);
        assertTrue(store.find(job.id()).isEmpty());
        assertFalse(store.markExpired(job.id()));
        useTenant(ORG_A);
        assertTrue(store.find(job.id()).isPresent());
        assertTrue(store.markExpired(job.id()));
        assertEquals("EXPIRED", store.find(job.id()).orElseThrow().status());
    }
}
