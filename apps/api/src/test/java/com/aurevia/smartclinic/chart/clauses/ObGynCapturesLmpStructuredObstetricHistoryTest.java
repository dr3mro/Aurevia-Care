package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.clinical.templates.BaselineTemplates;
import com.aurevia.smartclinic.clinical.templates.FieldDefinition;
import com.aurevia.smartclinic.clinical.templates.FieldType;
import com.aurevia.smartclinic.clinical.templates.FieldValue;
import com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore;
import com.aurevia.smartclinic.clinical.templates.SavedNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateActor;
import com.aurevia.smartclinic.clinical.templates.TemplateKind;
import com.aurevia.smartclinic.clinical.templates.TemplateNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateService;
import com.aurevia.smartclinic.clinical.templates.TemplateStore;
import com.aurevia.smartclinic.clinical.templates.TemplateVersion;
import com.aurevia.smartclinic.clinical.templates.ValidationReport;
import com.aurevia.smartclinic.tenant.TenantContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CLN-04 clause 3 (API evidence): the OB/GYN template captures a required ISO
 * last menstrual period and a required obstetric-history code drawn from the
 * governance-approved site dictionary.
 *
 * <p>There is no CLN-04 HTTP controller, so this suite drives the production
 * {@link com.aurevia.smartclinic.clinical.templates.TemplateService} over the real
 * tenant-scoped {@link com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore}
 * on a fresh PostgreSQL database created from the production Flyway migrations.
 * Every command is wrapped in the tenant-guarded transaction so the forced RLS
 * context ({@code app.current_org} and {@code app.actor}) is bound to the same
 * connection as the write; without it the store would read nothing under
 * FORCE RLS. The positive test asserts the saved values, the frozen field
 * meanings and the frozen dictionary snapshot, and checks the persisted row.
 * Negative tests show that the rule refuses a wrong role, a cross-tenant read,
 * a version approved before its dictionary, an out-of-dictionary code, a
 * non-ISO date and a missing required date.</p>
 *
 * <p>All identifiers, codes and subjects are synthetic. No real patient data,
 * credential or token is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_obgyn_c03_it")
@ActiveProfiles("synthetic-auth")
class ObGynCapturesLmpStructuredObstetricHistoryTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DICTIONARY_KEY = "obstetric_history";
    private static final List<String> SITE_CODES = List.of("G0P0", "G1P0", "G2P0", "G3P0", "G4P0");
    private static final String PATIENT_REF = "PAT-SYNTHETIC-OBGYN";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_obgyn_c03_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TemplateService service;

    @Autowired
    TemplateStore store;

    @Autowired
    TransactionTemplate transactions;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic memberships for the two tenants used by the proofs.
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_obgyn_c03_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln04_obgyn_c03_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
            seedUser(statement, ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
            seedUser(statement, ORG_A, "clin-a", TemplateService.CLINICIAN);
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "admin-b", TemplateService.TENANT_ADMIN);
            seedUser(statement, ORG_B, "gov-b", TemplateService.CLINICAL_GOVERNANCE);
            seedUser(statement, ORG_B, "clin-b", TemplateService.CLINICIAN);
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_obgyn_c03_it WITH (FORCE)");
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
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = '" + org + "'");
    }

    /**
     * Run one service command as a freshly bound tenant inside the guarded
     * transaction, exactly as the request interceptor does for a real caller.
     *
     * @param org     active organization identifier
     * @param subject authenticated synthetic subject
     * @param action  command to execute in that tenant
     * @param <T>     result type
     * @return the command result
     */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "TEMPLATE_EVIDENCE", "cln04-obgyn-c03"));
        try {
            return transactions.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    private static TemplateActor admin(String org, String subject) {
        return TemplateActor.of(org, subject, TemplateService.TENANT_ADMIN);
    }

    private static TemplateActor governor(String org, String subject) {
        return TemplateActor.of(org, subject, TemplateService.CLINICAL_GOVERNANCE);
    }

    private static TemplateActor clinician(String org, String subject) {
        return TemplateActor.of(org, subject, TemplateService.CLINICIAN);
    }

    /**
     * Define and approve the obstetric dictionary and one OB/GYN version for the
     * primary tenant. Duplicate-definition conflicts are tolerated so the proof is
     * independent of test execution order.
     *
     * @return the approved version number
     */
    private int approveObgynBaseline() {
        TemplateActor adminA = admin(ORG_A, "admin-a");
        TemplateActor governorA = governor(ORG_A, "gov-a");
        as(ORG_A, "admin-a", () -> {
            try {
                service.defineDictionary(adminA, DICTIONARY_KEY, SITE_CODES);
            } catch (ApiException alreadyDefined) {
                assertEquals(409, alreadyDefined.status(), alreadyDefined.getMessage());
            }
            return null;
        });
        as(ORG_A, "gov-a", () -> {
            try {
                service.approveDictionary(governorA, DICTIONARY_KEY);
            } catch (ApiException alreadyApproved) {
                assertEquals(409, alreadyApproved.status(), alreadyApproved.getMessage());
            }
            return null;
        });
        TemplateVersion version = as(ORG_A, "admin-a", () -> service.defineVersion(
                adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN)));
        as(ORG_A, "gov-a", () -> service.approveVersion(governorA, TemplateKind.OBGYN, version.version()));
        return version.version();
    }

    /**
     * Build an OB/GYN note revision; a null date or code omits that field so the
     * validation rule for a missing required value can be exercised.
     *
     * @param version template version the note binds to
     * @param lmp     ISO last menstrual period, or null to omit
     * @param code    obstetric-history code, or null to omit
     * @return the synthetic note revision
     */
    private static TemplateNoteRevision note(int version, String lmp, String code) {
        List<FieldValue> values = new ArrayList<>();
        if (lmp != null) {
            values.add(new FieldValue("lmp", lmp, null));
        }
        if (code != null) {
            values.add(new FieldValue("obstetricHistory", code, null));
        }
        return new TemplateNoteRevision(TemplateKind.OBGYN, version, PATIENT_REF, "clin-a", values);
    }

    /**
     * Count persisted rows for one synthetic note id in the primary tenant. The
     * migration owner is also subject to FORCE RLS, so bind the tenant first.
     *
     * @param noteId tenant-local note identifier
     * @return number of persisted rows
     * @throws Exception when the database cannot be read
     */
    private static int countRows(String noteId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.template_note_revision WHERE note_id = '" + noteId + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }

    @Test
    void clinicianSavesObgynNoteWithIsoLmpAndApprovedDictionaryCode() throws Exception {
        assertTrue(store instanceof JdbcTemplateStore,
                "the production TemplateStore bean must be the PostgreSQL adapter");
        int version = approveObgynBaseline();
        String noteId = "NOTE-OBGYN-ISO-LMP";
        SavedNoteRevision saved = as(ORG_A, "clin-a", () ->
                service.saveNote(clinician(ORG_A, "clin-a"), noteId,
                        note(version, "2026-10-01", "G1P0")));

        assertEquals(ORG_A, saved.organizationId());
        assertEquals(noteId, saved.noteId());
        assertEquals(TemplateKind.OBGYN, saved.note().kind());
        assertEquals(version, saved.note().templateVersion());
        assertEquals("2026-10-01", fieldValue(saved, "lmp"));
        assertEquals("G1P0", fieldValue(saved, "obstetricHistory"));

        FieldDefinition lmp = field(saved.fieldMeanings(), "lmp");
        assertEquals(FieldType.DATE, lmp.type());
        assertTrue(lmp.required());
        assertEquals("last menstrual period", lmp.label());
        FieldDefinition history = field(saved.fieldMeanings(), "obstetricHistory");
        assertEquals(FieldType.CODE, history.type());
        assertTrue(history.required());
        assertEquals(DICTIONARY_KEY, history.dictionaryKey());
        assertEquals(SITE_CODES, saved.dictionaries().get(DICTIONARY_KEY));

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT kind, template_version, author, patient_ref, "
                            + "values::text, field_meanings::text, dictionaries::text "
                            + "FROM public.template_note_revision WHERE note_id = '" + noteId + "'")) {
                assertTrue(result.next());
                assertEquals("OBGYN", result.getString("kind"));
                assertEquals(version, result.getInt("template_version"));
                assertEquals("clin-a", result.getString("author"));
                assertEquals(PATIENT_REF, result.getString("patient_ref"));
                List<FieldValue> persistedValues = JSON.readValue(result.getString("values"),
                        new TypeReference<List<FieldValue>>() { });
                assertEquals("2026-10-01", persistedValues.stream()
                        .filter(value -> "lmp".equals(value.fieldKey())).findFirst().orElseThrow().value());
                assertEquals("G1P0", persistedValues.stream()
                        .filter(value -> "obstetricHistory".equals(value.fieldKey()))
                        .findFirst().orElseThrow().value());
                assertTrue(result.getString("field_meanings").contains("last menstrual period"));
                assertTrue(result.getString("field_meanings").contains(DICTIONARY_KEY));
                assertTrue(result.getString("dictionaries").contains("G4P0"));
            }
        }
    }

    @Test
    void wrongRoleCannotSaveAnObgynNote() throws Exception {
        int version = approveObgynBaseline();
        String noteId = "NOTE-OBGYN-WRONG-ROLE";
        ApiException denied = assertThrows(ApiException.class, () -> as(ORG_A, "reception-a", () ->
                service.saveNote(TemplateActor.of(ORG_A, "reception-a", "reception"), noteId,
                        note(version, "2026-10-01", "G1P0"))));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
        assertEquals(0, countRows(noteId));
    }

    @Test
    void crossTenantActorCannotReadTheSavedObgynNote() throws Exception {
        int version = approveObgynBaseline();
        String noteId = "NOTE-OBGYN-CROSS-TENANT";
        as(ORG_A, "clin-a", () -> service.saveNote(clinician(ORG_A, "clin-a"), noteId,
                note(version, "2026-10-01", "G0P0")));

        ApiException denied = assertThrows(ApiException.class, () -> as(ORG_B, "clin-b", () ->
                service.savedNote(clinician(ORG_B, "clin-b"), noteId)));
        assertEquals(404, denied.status());
        assertEquals("NOT_FOUND", denied.code());
        assertEquals(1, countRows(noteId), "the owning tenant's row stays unchanged");
    }

    @Test
    void templateVersionWithUnapprovedDictionaryCannotBeApproved() {
        TemplateActor adminB = admin(ORG_B, "admin-b");
        TemplateActor governorB = governor(ORG_B, "gov-b");
        as(ORG_B, "admin-b", () -> {
            try {
                service.defineDictionary(adminB, DICTIONARY_KEY, SITE_CODES);
            } catch (ApiException alreadyDefined) {
                assertEquals(409, alreadyDefined.status(), alreadyDefined.getMessage());
            }
            return null;
        });
        TemplateVersion pending = as(ORG_B, "admin-b", () -> service.defineVersion(
                adminB, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN)));
        assertFalse(pending.approved());

        ApiException denied = assertThrows(ApiException.class, () -> as(ORG_B, "gov-b", () ->
                service.approveVersion(governorB, TemplateKind.OBGYN, pending.version())));
        assertEquals(422, denied.status());
        assertEquals("DICTIONARY_NOT_APPROVED", denied.code());
    }

    @Test
    void outOfDictionaryCodeAndMalformedOrMissingLmpAreRejected() throws Exception {
        int version = approveObgynBaseline();
        String noteId = "NOTE-OBGYN-INVALID";

        ValidationReport outOfDictionary = as(ORG_A, "clin-a", () -> service.validate(
                clinician(ORG_A, "clin-a"), note(version, "2026-10-01", "G9P9")));
        assertFalse(outOfDictionary.valid());
        assertTrue(outOfDictionary.violations().contains("dictionary:obstetricHistory"),
                outOfDictionary.violations().toString());

        ValidationReport malformedDate = as(ORG_A, "clin-a", () -> service.validate(
                clinician(ORG_A, "clin-a"), note(version, "01/10/2026", "G1P0")));
        assertFalse(malformedDate.valid());
        assertTrue(malformedDate.violations().contains("format:lmp"),
                malformedDate.violations().toString());

        ValidationReport missingDate = as(ORG_A, "clin-a", () -> service.validate(
                clinician(ORG_A, "clin-a"), note(version, null, "G1P0")));
        assertFalse(missingDate.valid());
        assertTrue(missingDate.violations().contains("missing:lmp"),
                missingDate.violations().toString());

        ApiException denied = assertThrows(ApiException.class, () -> as(ORG_A, "clin-a", () ->
                service.saveNote(clinician(ORG_A, "clin-a"), noteId,
                        note(version, "01/10/2026", "G9P9"))));
        assertEquals(422, denied.status());
        assertEquals("INVALID_NOTE", denied.code());
        assertEquals(0, countRows(noteId));
    }

    private static String fieldValue(SavedNoteRevision saved, String key) {
        return saved.note().values().stream()
                .filter(value -> key.equals(value.fieldKey()))
                .findFirst().orElseThrow().value();
    }
}
