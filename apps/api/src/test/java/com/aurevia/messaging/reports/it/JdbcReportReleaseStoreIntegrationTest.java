package com.aurevia.messaging.reports.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.reports.JdbcReportReleaseStore;
import com.aurevia.messaging.reports.PatientChartLink;
import com.aurevia.messaging.reports.ReportRelease;
import com.aurevia.messaging.reports.ReleasedReport;
import com.aurevia.messaging.reports.ReleasedReport.CorrectionContext;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportController;
import com.aurevia.messaging.reports.ReleasedReportService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * XCH-06 / PAT-02 / ARC-02 acceptance for the JDBC report-release store.
 *
 * <p>Requests go through the real {@code ReleasedReportController} with the JDBC
 * adapter and a real PostgreSQL database. Two tenants store identical local
 * patient and subject identifiers, so a read that escapes tenant scope is
 * observable. Every fixture is synthetic; forced row-level security from V84 is
 * the isolation boundary under test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_report_release_it")
@ActiveProfiles("synthetic-auth")
class JdbcReportReleaseStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String SUBJECT = "patient-shared";
    static final String PATIENT = "patient-shared";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_report_release_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final Instant DECIDED = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired
    JdbcReportReleaseStore store;

    @Autowired
    ReleasedReportService service;

    private MockMvc mvc;

    /** Create a fresh database, apply all migrations and seed two tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_report_release_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_report_release_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_report_release_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SUBJECT + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + SUBJECT + "', 'synthetic patient')");
    }

    @BeforeEach
    void initMvc() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new ReleasedReportController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private <T> T as(String org, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, SUBJECT, "PATIENT_REPORT_ACCESS", "report-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void runAs(String org, Runnable action) {
        as(org, () -> {
            action.run();
            return null;
        });
    }

    private static ReleasedReport report(String reportId, String org, int revision, Status status,
                                         String effectiveDate, String documentId,
                                         CorrectionContext correction) {
        return new ReleasedReport(reportId, revision, PATIENT, org, "Clinic A", effectiveDate,
                "en", documentId, "checksum-" + documentId, status, correction);
    }

    private static ReportRelease release(ReleasedReport revision, int policyVersion) {
        return new ReportRelease(revision, policyVersion, DECIDED, null, null, DECIDED);
    }

    private void linkAndRelease(String org, String reportId, String documentId) {
        as(org, () -> {
            store.putLink(org, SUBJECT, new PatientChartLink(PATIENT, true, true));
            ReleasedReport revision = report(reportId, org, 1, Status.RELEASED, "2026-06-10", documentId, null);
            store.putReport(revision);
            store.recordRelease(release(revision, 3));
            return null;
        });
    }

    private static void assertRejectedWithState(String sqlState, Statement statement, String sql)
            throws Exception {
        SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
        assertEquals(sqlState, failure.getSQLState(), sql);
    }

    private static int rowCount(Statement statement, String table, String organization) throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT count(*) FROM public." + table + " WHERE organization_id = '" + organization + "'")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    @Test
    void withdrawnReleaseStopsTheNextDownloadAndKeepsItsHistory() throws Exception {
        String reportId = "report-withdraw";
        linkAndRelease(ORG_A, reportId, "document-a");
        mvc.perform(get("/api/v1/patient/reports/{id}/download", reportId)
                        .param("revision", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("document-a"));

        assertTrue(as(ORG_A, () -> store.withdraw(ORG_A, reportId, 1)));

        mvc.perform(get("/api/v1/patient/reports/{id}/download", reportId)
                        .param("revision", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RELEASE_WITHDRAWN"));
        String listed = mvc.perform(get("/api/v1/patient/reports")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(listed.contains("report-withdraw"));

        List<ReportRelease> history = as(ORG_A, () -> store.releaseHistory(ORG_A, reportId, 1));
        assertEquals(2, history.size());
        assertEquals(Status.RELEASED, history.get(0).revision().status());
        assertEquals(Status.WITHDRAWN, history.get(1).revision().status());
        assertEquals(3, history.get(0).policyVersion());
    }

    @Test
    void correctedReleaseUpdatesCurrentAccessAndAppendsItsOwnDecision() throws Exception {
        String reportId = "report-correct";
        linkAndRelease(ORG_A, reportId, "document-a");
        as(ORG_A, () -> {
            ReleasedReport correction = report(reportId, ORG_A, 2, Status.RELEASED, "2026-06-12",
                    "document-corrected", new CorrectionContext(1, "corrected value"));
            store.putReport(correction);
            store.recordRelease(release(correction, 4));
            return null;
        });

        mvc.perform(get("/api/v1/patient/reports/{id}/download", reportId)
                        .param("revision", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"));
        mvc.perform(get("/api/v1/patient/reports/{id}/download", reportId)
                        .param("revision", "2")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("document-corrected"));

        assertEquals(1, as(ORG_A, () -> store.releaseHistory(ORG_A, reportId, 1)).size());
        assertEquals(1, as(ORG_A, () -> store.releaseHistory(ORG_A, reportId, 2)).size());
    }

    @Test
    void identicalLocalIdentifiersStayIsolatedAcrossTenants() throws Exception {
        String reportId = "report-isolation";
        linkAndRelease(ORG_A, reportId, "document-a");
        linkAndRelease(ORG_B, reportId, "document-b");

        mvc.perform(get("/api/v1/patient/reports/{id}/download", reportId)
                        .param("revision", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("document-a"));
        mvc.perform(get("/api/v1/patient/reports/{id}/download", reportId)
                        .param("revision", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("document-b"));
        assertTrue(as(ORG_A, () -> store.reportsFor(ORG_A, PATIENT)).stream()
                .allMatch(revision -> ORG_A.equals(revision.organizationId())));
        assertTrue(as(ORG_B, () -> store.reportsFor(ORG_B, PATIENT)).stream()
                .allMatch(revision -> ORG_B.equals(revision.organizationId())));
        assertEquals("document-a", as(ORG_A, () -> store.find(ORG_A, reportId, 1))
                .orElseThrow().documentId());
        assertEquals("document-b", as(ORG_B, () -> store.find(ORG_B, reportId, 1))
                .orElseThrow().documentId());
        assertTrue(as(ORG_A, () -> store.find(ORG_B, reportId, 1)).isEmpty());
        assertTrue(as(ORG_B, () -> store.find(ORG_A, reportId, 1)).isEmpty());
    }

    @Test
    void heldReleaseRecordsItsReasonAndExpiry() {
        String reportId = "report-held";
        as(ORG_A, () -> {
            store.putLink(ORG_A, SUBJECT, new PatientChartLink(PATIENT, true, true));
            ReleasedReport held = report(reportId, ORG_A, 7, Status.HELD, "2026-06-10",
                    "document-held", null);
            store.putReport(held);
            ReportRelease fact = new ReportRelease(held, 9, DECIDED,
                    "pending policy review", Instant.parse("2026-06-20T00:00:00Z"), DECIDED);
            store.recordRelease(fact);
            List<ReportRelease> history = store.releaseHistory(ORG_A, reportId, 7);
            assertEquals(1, history.size());
            assertEquals(9, history.get(0).policyVersion());
            assertEquals("pending policy review", history.get(0).holdReason());
            assertEquals(Instant.parse("2026-06-20T00:00:00Z"), history.get(0).holdExpiresAt());
            return null;
        });
    }

    @Test
    void releaseHistoryIsAppendOnlyForEveryRole() throws Exception {
        String reportId = "report-immutable";
        linkAndRelease(ORG_B, reportId, "document-b");
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // FORCE RLS hides rows without the GUC. Bind the tenant so a mutation
            // actually matches a row and the append-only trigger must fire.
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            assertTrue(rowCount(statement, "report_release_history", ORG_B) > 0);
            assertRejectedWithState("44000", statement,
                    "UPDATE public.report_release_history SET status = 'WITHDRAWN'");
            assertRejectedWithState("44000", statement, "DELETE FROM public.report_release_history");
            assertRejectedWithState("44000", statement, "TRUNCATE public.report_release_history");
        }
        assertEquals(1, as(ORG_B, () -> store.releaseHistory(ORG_B, reportId, 1)).size());
    }

    @Test
    void revisionIdentityIsImmutableButAccessStateCanAdvance() throws Exception {
        String reportId = "report-identity";
        linkAndRelease(ORG_A, reportId, "document-a");
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertTrue(rowCount(statement, "report_release_revision", ORG_A) > 0);
            String where = " WHERE organization_id = '" + ORG_A + "' AND id = '" + reportId
                    + "' AND revision = 1";
            assertRejectedWithState("42501", statement,
                    "UPDATE public.report_release_revision SET document_id = 'tampered'" + where);
            assertRejectedWithState("42501", statement,
                    "UPDATE public.report_release_revision SET checksum = 'tampered'" + where);
            assertRejectedWithState("42501", statement,
                    "UPDATE public.report_release_revision SET id = 'tampered'" + where);
            assertRejectedWithState("42501", statement,
                    "UPDATE public.report_release_revision SET revision = 9" + where);
            assertRejectedWithState("42501", statement,
                    "UPDATE public.report_release_revision SET patient_id = 'other'" + where);
            assertRejectedWithState("44000", statement,
                    "DELETE FROM public.report_release_revision" + where);
            // TRUNCATE is refused by the history foreign key before a trigger
            // could run; assert that it cannot erase the revision rows.
            assertThrows(SQLException.class, () -> statement.execute("TRUNCATE public.report_release_revision"));
            assertEquals(1, statement.executeUpdate(
                    "UPDATE public.report_release_revision SET status = 'WITHDRAWN', policy_version = 99"
                            + where));
        }
        assertEquals(Status.WITHDRAWN, as(ORG_A, () -> store.find(ORG_A, reportId, 1))
                .orElseThrow().status());
    }

    @Test
    void writesForAnotherTenantAreRejectedByThePolicyCheck() {
        String reportId = "report-cross-tenant";
        String foreignSubject = "foreign-subject";
        TenantContext.set(new TenantContext(ORG_A, SUBJECT, "PATIENT_REPORT_ACCESS", "report-it"));
        try {
            assertThrows(DataAccessException.class, () -> store.putLink(ORG_B, foreignSubject,
                    new PatientChartLink(PATIENT, true, true)));
            assertThrows(DataAccessException.class, () -> store.putReport(
                    report(reportId, ORG_B, 1, Status.RELEASED, "2026-06-10", "document-foreign", null)));
        } finally {
            TenantContext.clear();
        }
        assertTrue(as(ORG_B, () -> store.link(ORG_B, foreignSubject)).isEmpty());
        assertTrue(as(ORG_B, () -> store.find(ORG_B, reportId, 1)).isEmpty());
    }

    @Test
    void aWithdrawnRevisionCannotBeReleasedAgain() throws Exception {
        String reportId = "report-terminal";
        linkAndRelease(ORG_A, reportId, "document-a");
        assertTrue(as(ORG_A, () -> store.withdraw(ORG_A, reportId, 1)));

        ReleasedReport reRelease = report(reportId, ORG_A, 1, Status.RELEASED,
                "2026-06-10", "document-a", null);
        assertThrows(IllegalStateException.class,
                () -> runAs(ORG_A, () -> store.recordRelease(release(reRelease, 5))));

        mvc.perform(get("/api/v1/patient/reports/{id}/download", reportId)
                        .param("revision", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isGone());
        assertEquals(2, as(ORG_A, () -> store.releaseHistory(ORG_A, reportId, 1)).size());

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertRejectedWithState("23514", statement,
                    "UPDATE public.report_release_revision SET status = 'RELEASED'"
                            + " WHERE id = '" + reportId + "' AND revision = 1");
        }
    }
}
