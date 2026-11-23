package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.clinical.templates.BaselineTemplates;
import com.aurevia.smartclinic.clinical.templates.FieldValue;
import com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore;
import com.aurevia.smartclinic.clinical.templates.SavedNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateActor;
import com.aurevia.smartclinic.clinical.templates.TemplateKind;
import com.aurevia.smartclinic.clinical.templates.TemplateNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateService;
import com.aurevia.smartclinic.clinical.templates.ValidationReport;

/**
 * CLN-04 clause 5 proof: a pediatric development note captures milestone domain,
 * observation and date. The tests drive the real
 * {@link com.aurevia.smartclinic.clinical.templates.TemplateService} over a synthetic
 * in-memory store, because the rule is implemented in the domain and persistence
 * layers. The HTTP entry point does not exist yet; the missing surface is described
 * in the clause note's gap. Synthetic fixtures only; this suite adds no production
 * code and changes no existing test.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_development_c05_it")
@ActiveProfiles("synthetic-auth")
class DevelopmentCapturesMilestoneDomainObservationDateIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_development_c05_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private final TemplateActor adminA = TemplateActor.of(ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governorA = TemplateActor.of(ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinicianA = TemplateActor.of(ORG_A, "clinician-a", TemplateService.CLINICIAN);
    private final TemplateActor receptionA = TemplateActor.of(ORG_A, "reception-a", "reception");
    private final TemplateActor clinicianB = TemplateActor.of(ORG_B, "clinician-b", TemplateService.CLINICIAN);

    TemplateService service;

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_development_c05_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln04_development_c05_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_A, "gov-a", "clinical_governance");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedUser(statement, ORG_B, "reception-b", "reception");
            seedPatient(statement, ORG_A, "Synthetic Development Alpha");
            seedPatient(statement, ORG_B, "Synthetic Development Beta");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_development_c05_it WITH (FORCE)");
        }
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
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPatient(Statement statement, String org, String name) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'reception-" + (ORG_A.equals(org) ? "a" : "b") + "'");
        // V7 requires exactly one of dob / dob_unknown_reason; a synthetic civil
        // date satisfies that append-only invariant before any behaviour runs.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-DEVELOPMENT', 'CLINIC', '"
                + name + "', '" + name.toLowerCase() + "', DATE '1990-05-20')");
    }

    @BeforeEach
    void init() {
        service = new TemplateService(new InMemoryTemplateStore());
    }

    private void approveDevelopmentBaseline() {
        service.defineDictionary(adminA, "milestone_domain",
                List.of("gross_motor", "fine_motor", "language"));
        service.approveDictionary(governorA, "milestone_domain");
        service.defineVersion(adminA, TemplateKind.PEDIATRIC_DEVELOPMENT,
                BaselineTemplates.fieldsFor(TemplateKind.PEDIATRIC_DEVELOPMENT));
        service.approveVersion(governorA, TemplateKind.PEDIATRIC_DEVELOPMENT, 1);
    }

    private static TemplateNoteRevision developmentNote(
            int version, String domain, String observation, String language, String date) {
        return new TemplateNoteRevision(TemplateKind.PEDIATRIC_DEVELOPMENT, version, PATIENT, "clinician-a",
                List.of(new FieldValue("milestoneDomain", domain, null),
                        new FieldValue("observation", observation, language),
                        new FieldValue("date", date, null)));
    }

    private static Map<String, FieldValue> values(SavedNoteRevision saved) {
        return saved.note().values().stream()
                .collect(Collectors.toMap(FieldValue::fieldKey, value -> value));
    }

    @Test
    void capturesMilestoneDomainObservationAndDateInAPediatricDevelopmentNote() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote(1, "gross_motor", "sits unsupported", "en", "2026-10-03");

        ValidationReport report = service.validate(clinicianA, note);
        assertTrue(report.valid(), "the full development note must validate: " + report.violations());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinicianA, "DEV-001", note);
        Map<String, FieldValue> values = values(saved);
        assertEquals("gross_motor", values.get("milestoneDomain").value());
        assertEquals("sits unsupported", values.get("observation").value());
        assertEquals("2026-10-03", values.get("date").value());
        assertEquals("en", values.get("observation").language(), "the free-text observation keeps its language");

        assertEquals(BaselineTemplates.fieldsFor(TemplateKind.PEDIATRIC_DEVELOPMENT), saved.fieldMeanings());
        assertEquals(List.of("gross_motor", "fine_motor", "language"),
                saved.dictionaries().get("milestone_domain"), "the approved dictionary is frozen with the note");
        assertEquals(ORG_A, saved.organizationId());
        assertEquals(saved, service.savedNote(clinicianA, "DEV-001"),
                "the saved revision must round-trip through the tenant-scoped store");
    }

    @Test
    void refusesNoteOmittingTheObservation() {
        approveDevelopmentBaseline();
        TemplateNoteRevision incomplete = new TemplateNoteRevision(TemplateKind.PEDIATRIC_DEVELOPMENT, 1,
                PATIENT, "clinician-a", List.of(
                        new FieldValue("milestoneDomain", "gross_motor", null),
                        new FieldValue("date", "2026-10-03", null)));

        ValidationReport report = service.validate(clinicianA, incomplete);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("missing:observation"),
                "the required observation must be named: " + report.violations());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(clinicianA, "DEV-PARTIAL", incomplete));
        assertEquals(422, denied.status());
        assertEquals("INVALID_NOTE", denied.code());
        assertEquals(404, status(() -> service.savedNote(clinicianA, "DEV-PARTIAL")),
                "an incomplete note must not be persisted");
    }

    @Test
    void refusesMilestoneCodeOutsideTheApprovedDictionary() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note =
                developmentNote(1, "unapproved_milestone", "sits unsupported", "en", "2026-10-03");

        ValidationReport report = service.validate(clinicianA, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("dictionary:milestoneDomain"),
                "a milestone code outside the approved dictionary must be refused: " + report.violations());
        assertEquals(422, status(() -> service.saveNote(clinicianA, "DEV-BAD-CODE", note)));
    }

    @Test
    void refusesNoteSaveByNonClinicianRole() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote(1, "gross_motor", "sits unsupported", "en", "2026-10-03");

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(receptionA, "DEV-BAD-ROLE", note));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
        assertEquals(404, status(() -> service.savedNote(clinicianA, "DEV-BAD-ROLE")),
                "a refused role must not persist a note");
    }

    @Test
    void concealsATemplateVersionFromAnotherTenant() {
        approveDevelopmentBaseline();
        assertEquals(1, service.effective(clinicianA, TemplateKind.PEDIATRIC_DEVELOPMENT).version(),
                "the version must exist in the authoring tenant before isolation is tested");
        assertEquals(404, status(() -> service.effective(clinicianB, TemplateKind.PEDIATRIC_DEVELOPMENT)));
        assertEquals(404, status(() -> service.validate(clinicianB,
                developmentNote(1, "gross_motor", "sits unsupported", "en", "2026-10-03"))));
        assertEquals(404, status(() -> service.saveNote(clinicianB, "DEV-CROSS-TENANT",
                developmentNote(1, "gross_motor", "sits unsupported", "en", "2026-10-03"))));
    }

    private static int status(ThrowingRunnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertNotNull(exception.code());
        return exception.status();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
