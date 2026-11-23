package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.clinical.templates.BaselineTemplates;
import com.aurevia.smartclinic.clinical.templates.FieldDefinition;
import com.aurevia.smartclinic.clinical.templates.FieldType;
import com.aurevia.smartclinic.clinical.templates.FieldValue;
import com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore;
import com.aurevia.smartclinic.clinical.templates.SavedNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateActor;
import com.aurevia.smartclinic.clinical.templates.TemplateKind;
import com.aurevia.smartclinic.clinical.templates.TemplateNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateService;
import com.aurevia.smartclinic.clinical.templates.TemplateVersion;
import com.aurevia.smartclinic.clinical.templates.ValidationReport;
import com.aurevia.smartclinic.tenant.TenantContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CLN-04 clause 1 acceptance: the chart can provide all five versioned clinical
 * note template families — SOAP, operative, OB/GYN, perinatal and pediatric
 * development. Every value is synthetic.
 *
 * <p>The domain service runs against its production JDBC store on real
 * PostgreSQL with Flyway V80/V87 applied, and tenant context is bound exactly
 * as the HTTP layer binds it, so a version really survives a store round trip
 * and a second tenant cannot see the first tenant's definitions. Each test gets
 * its own pair of synthetic organizations so per-family version histories do
 * not leak between tests. The negatives prove the governance boundary (wrong
 * role) and the lookup boundary (another tenant and an unknown version) refuse
 * what the clause does not grant. A second group of tests below proves the
 * note-save, dictionary-governance and required-field rules at the domain
 * boundary with the synthetic in-memory store, preserving the parallel
 * evidence worker's assessment of the same clause. This class proves only
 * clause 1; the field-level clauses have their own tests.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_c01_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ProvideVersionedSoapOperativeObGynPerinatalTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_c01_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private static final Map<String, List<String>> SITE_DICTIONARIES = Map.of(
            "obstetric_history", List.of("G0P0", "G1P0", "G2P0"),
            "milestone_domain", List.of("gross_motor", "fine_motor", "language"));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<FieldDefinition>> FIELDS = new TypeReference<>() { };
    private static final TypeReference<Map<String, List<String>>> DICTIONARIES = new TypeReference<>() { };

    @Autowired
    TemplateService templates;

    @Autowired
    TransactionTemplate transactions;

    private String orgA;
    private String orgB;
    private TemplateActor adminA;
    private TemplateActor governorA;
    private TemplateActor clinicianA;
    private TemplateActor clinicianB;

    /** Recreate a fresh database and apply the template migrations once for the suite. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_c01_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln04_c01_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
    }

    /** Give each test two fresh tenants and the actors that represent their staff. */
    @BeforeEach
    void freshTenants() throws Exception {
        orgA = UUID.randomUUID().toString();
        orgB = UUID.randomUUID().toString();
        adminA = TemplateActor.of(orgA, "admin-a", TemplateService.TENANT_ADMIN);
        governorA = TemplateActor.of(orgA, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
        clinicianA = TemplateActor.of(orgA, "clin-a", TemplateService.CLINICIAN);
        clinicianB = TemplateActor.of(orgB, "clin-b", TemplateService.CLINICIAN);
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : List.of(orgA, orgB)) {
                statement.execute("SET app.current_org = '" + org + "'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                        + org + "', 'Clause tenant')");
            }
        }
    }

    /** Drop the synthetic database so a later suite starts clean. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_c01_it WITH (FORCE)");
        }
    }

    /**
     * Run one command with the tenant bound as the HTTP layer binds it.
     *
     * <p>Forced RLS resolves the active organization from the transaction-local
     * GUC, so setting {@code TenantContext} and using the tenant-guarded
     * transaction template is what makes the production JDBC store see only
     * this tenant's rows.</p>
     *
     * @param org     owning organization identifier
     * @param subject synthetic authenticated subject
     * @param action  command to run in the tenant scope
     * @param <T>     result type
     * @return the command result
     */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "CLN04_CLAUSE", "cln04-clause-" + org));
        try {
            return transactions.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Define and govern the first durable version of one family from the approved
     * baseline. A CODE field's site dictionary must be approved before the version
     * can be. The version number comes from the store rather than being assumed:
     * the JDBC sequence and the in-memory fake number their first version
     * differently, and the clause does not prescribe a starting number.
     *
     * @param kind template family to publish
     * @return the version number that was defined and approved
     */
    private int approveBaseline(TemplateKind kind) {
        return as(orgA, "gov-a", () -> {
            Set<String> defined = new HashSet<>();
            for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
                if (field.type() == FieldType.CODE && defined.add(field.dictionaryKey())) {
                    List<String> entries = SITE_DICTIONARIES.get(field.dictionaryKey());
                    assertTrue(entries != null, "no synthetic entries for " + field.dictionaryKey());
                    templates.defineDictionary(adminA, field.dictionaryKey(), entries);
                    templates.approveDictionary(governorA, field.dictionaryKey());
                }
            }
            TemplateVersion created = templates.defineVersion(
                    adminA, kind, BaselineTemplates.fieldsFor(kind));
            templates.approveVersion(governorA, kind, created.version());
            return created.version();
        });
    }

    /**
     * The durable V87 catalog really ships one approved baseline per family.
     * Without this assertion the Java baseline could drift from the persisted
     * governance catalog and the other tests would still pass.
     *
     * @throws Exception when the catalog cannot be read or parsed
     */
    @Test
    void theDurableBaselineCatalogProvidesEveryFamily() throws Exception {
        Map<TemplateKind, List<FieldDefinition>> catalogFields = new HashMap<>();
        Map<String, List<String>> catalogDictionaries = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT kind, fields::text, dictionaries::text "
                             + "FROM public.current_clinical_template_baseline")) {
            while (rows.next()) {
                TemplateKind kind = TemplateKind.valueOf(rows.getString("kind"));
                assertTrue(catalogFields.put(kind,
                        JSON.readValue(rows.getString("fields"), FIELDS)) == null,
                        "duplicate durable baseline for " + kind);
                catalogDictionaries.putAll(JSON.readValue(rows.getString("dictionaries"), DICTIONARIES));
            }
        }
        assertEquals(TemplateKind.values().length, catalogFields.size(),
                "the durable catalog must provide all five families");
        for (TemplateKind kind : TemplateKind.values()) {
            assertEquals(BaselineTemplates.fieldsFor(kind), catalogFields.get(kind),
                    kind + " durable fields must match the approved baseline");
        }
        for (String key : SITE_DICTIONARIES.keySet()) {
            assertTrue(catalogDictionaries.containsKey(key) && !catalogDictionaries.get(key).isEmpty(),
                    "the durable catalog must ship the " + key + " site dictionary");
        }
    }

    /** Every family exposes an approved version with its exact baseline fields. */
    @Test
    void everyFamilyProvidesAnApprovedVersionedTemplate() {
        for (TemplateKind kind : TemplateKind.values()) {
            int published = approveBaseline(kind);
            as(orgA, "clin-a", () -> {
                TemplateVersion effective = templates.effective(clinicianA, kind);
                assertEquals(published, effective.version(), kind + " effective version");
                assertTrue(effective.approved(), kind + " must be governance approved");
                assertEquals(BaselineTemplates.fieldsFor(kind), effective.fields(),
                        kind + " must keep its approved field meanings");
                assertEquals(List.of(effective), templates.history(clinicianA, kind),
                        kind + " version history");
                return null;
            });
        }
    }

    /** A later approved version becomes effective without rewriting an earlier one. */
    @Test
    void aNewApprovedVersionBecomesEffectiveWithoutRewritingEarlierVersions() {
        approveBaseline(TemplateKind.OPERATIVE);
        TemplateVersion before = as(orgA, "clin-a",
                () -> templates.effective(clinicianA, TemplateKind.OPERATIVE));

        List<FieldDefinition> revised = new ArrayList<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE)) {
            if ("procedure".equals(field.key())) {
                revised.add(new FieldDefinition(field.key(), "procedure (revised)", field.type(),
                        field.required(), field.dictionaryKey(), field.language()));
            } else {
                revised.add(field);
            }
        }
        int second = as(orgA, "gov-a", () -> {
            TemplateVersion created = templates.defineVersion(adminA, TemplateKind.OPERATIVE, revised);
            templates.approveVersion(governorA, TemplateKind.OPERATIVE, created.version());
            return created.version();
        });

        as(orgA, "clin-a", () -> {
            TemplateVersion current = templates.effective(clinicianA, TemplateKind.OPERATIVE);
            assertEquals(second, current.version(), "the newest approved version is effective");
            assertTrue(second > before.version(), "the second version is later than the first");
            assertEquals("procedure (revised)", current.fields().get(0).label());
            List<TemplateVersion> history = templates.history(clinicianA, TemplateKind.OPERATIVE);
            assertEquals(2, history.size(), "both versions are retained");
            assertEquals(before, history.get(0), "the first version is unchanged");
            assertEquals("procedure", history.get(0).fields().get(0).label());
            return null;
        });
    }

    /** A clinician may read templates but may not define a new version. */
    @Test
    void definingAVersionIsRefusedForAnActorWithoutTheDefineRole() {
        ApiException denied = as(orgA, "clin-a", () -> assertThrows(ApiException.class,
                () -> templates.defineVersion(clinicianA, TemplateKind.SOAP,
                        BaselineTemplates.fieldsFor(TemplateKind.SOAP))));
        assertEquals(403, denied.status());
        ApiException absent = as(orgA, "clin-a", () -> assertThrows(ApiException.class,
                () -> templates.history(clinicianA, TemplateKind.SOAP)));
        assertEquals(404, absent.status(), "a refused definition leaves no version behind");
    }

    /** A version published by one tenant is concealed from another tenant. */
    @Test
    void aVersionFromAnotherTenantIsNotVisible() {
        approveBaseline(TemplateKind.SOAP);
        ApiException concealed = as(orgB, "clin-b", () -> assertThrows(ApiException.class,
                () -> templates.effective(clinicianB, TemplateKind.SOAP)));
        assertEquals(404, concealed.status());
        assertEquals("NOT_FOUND", concealed.code());
    }

    /** A note bound to an unknown version is refused instead of silently accepted. */
    @Test
    void aNoteAgainstAnUnknownVersionIsRefused() {
        approveBaseline(TemplateKind.SOAP);
        TemplateNoteRevision stale = new TemplateNoteRevision(
                TemplateKind.SOAP, 9, "PAT-001", "clin-a", List.of());
        ApiException refused = as(orgA, "clin-a", () -> assertThrows(ApiException.class,
                () -> templates.validate(clinicianA, stale)));
        assertEquals(404, refused.status());
        assertEquals("NOT_FOUND", refused.code());
    }

    // ------------------------------------------------------------------------
    // Domain-boundary evidence contributed by the parallel worker for this
    // clause. These tests keep the synthetic in-memory store fixture and the
    // note-save, dictionary-governance and required-field scenarios so both
    // independent assessments of CLN-04 clause 1 remain provable together.
    // ------------------------------------------------------------------------

    /**
     * All five clause families define and approve a version, then validate and
     * save a note carrying each family's named fields at the domain boundary.
     */
    @Test
    void definesAndApprovesVersionedTemplatesForAllFiveFamilies() {
        DomainFixture fixture = new DomainFixture();
        for (TemplateKind kind : TemplateKind.values()) {
            fixture.approveBaseline(DomainFixture.ORG_A, fixture.adminA, fixture.governorA, kind);
            TemplateVersion effective = fixture.service.effective(fixture.clinicianA, kind);

            assertEquals(1, effective.version(), kind + " must adopt version one");
            assertTrue(effective.approved(), kind + " must be governed before use");
            assertEquals(BaselineTemplates.fieldsFor(kind), effective.fields());
            assertEquals(DomainFixture.CLAUSE_FIELDS.get(kind),
                    effective.fields().stream().map(FieldDefinition::key).toList(),
                    kind + " must carry the clause's named fields");

            TemplateNoteRevision full = DomainFixture.note(kind, 1, DomainFixture.syntheticValues(kind));
            ValidationReport report = fixture.service.validate(fixture.clinicianA, full);
            assertTrue(report.valid(), kind + " full note should validate: " + report.violations());
            assertTrue(report.violations().isEmpty(), kind + " must report no violations");

            String noteId = "NOTE-" + kind.name();
            SavedNoteRevision saved = fixture.service.saveNote(fixture.clinicianA, noteId, full);
            assertEquals(kind, saved.note().kind());
            assertEquals(DomainFixture.ORG_A, saved.organizationId());
            assertEquals(effective.fields(), saved.fieldMeanings(),
                    kind + " must freeze the exact version's field meanings");
            assertEquals(saved, fixture.service.savedNote(fixture.clinicianA, noteId));
        }

        // A newer approved version does not rewrite the meaning of an older note.
        List<FieldDefinition> later = List.of(new FieldDefinition("chiefConcern", "presenting concern",
                FieldType.FREE_TEXT, true, null, "en"));
        fixture.service.defineVersion(fixture.adminA, TemplateKind.SOAP, later);
        fixture.service.approveVersion(fixture.governorA, TemplateKind.SOAP, 2);
        assertEquals(2, fixture.service.history(fixture.clinicianA, TemplateKind.SOAP).size());
        assertTrue(fixture.service.validate(fixture.clinicianA,
                DomainFixture.note(TemplateKind.SOAP, 1, DomainFixture.syntheticValues(TemplateKind.SOAP))).valid(),
                "the exact version one stays valid after version two is approved");
    }

    /** A clinician may author notes but may not define the template or its dictionary. */
    @Test
    void refusesTemplateDefinitionByClinician() {
        DomainFixture fixture = new DomainFixture();
        assertEquals(403, DomainFixture.status(() -> fixture.service.defineDictionary(fixture.clinicianA,
                "obstetric_history", SITE_DICTIONARIES.get("obstetric_history"))));
        ApiException denied = assertThrows(ApiException.class, () -> fixture.service.defineVersion(
                fixture.clinicianA, TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP)));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
    }

    /** A template version approved in one tenant is invisible to a clinician in another. */
    @Test
    void doesNotReadAnotherTenantsTemplateVersion() {
        DomainFixture fixture = new DomainFixture();
        fixture.approveBaseline(DomainFixture.ORG_A, fixture.adminA, fixture.governorA, TemplateKind.OBGYN);
        assertEquals(404, DomainFixture.status(
                () -> fixture.service.effective(fixture.clinicianB, TemplateKind.OBGYN)));
        assertEquals(404, DomainFixture.status(() -> fixture.service.validate(fixture.clinicianB,
                DomainFixture.note(TemplateKind.OBGYN, 1, DomainFixture.syntheticValues(TemplateKind.OBGYN)))));
    }

    /** A CODE field's site dictionary must itself be governed before its version can be approved. */
    @Test
    void refusesApprovalWithoutAnApprovedSiteDictionary() {
        DomainFixture fixture = new DomainFixture();
        fixture.service.defineDictionary(fixture.adminA, "obstetric_history",
                SITE_DICTIONARIES.get("obstetric_history"));
        fixture.service.defineVersion(fixture.adminA, TemplateKind.OBGYN,
                BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        ApiException denied = assertThrows(ApiException.class,
                () -> fixture.service.approveVersion(fixture.governorA, TemplateKind.OBGYN, 1));
        assertEquals(422, denied.status());
        assertEquals("DICTIONARY_NOT_APPROVED", denied.code());
    }

    /** A note must bind to a version that exists; an unknown version is concealed. */
    @Test
    void refusesNoteAgainstUnknownVersion() {
        DomainFixture fixture = new DomainFixture();
        fixture.approveBaseline(DomainFixture.ORG_A, fixture.adminA, fixture.governorA, TemplateKind.OBGYN);
        assertTrue(fixture.service.validate(fixture.clinicianA,
                DomainFixture.note(TemplateKind.OBGYN, 1, DomainFixture.syntheticValues(TemplateKind.OBGYN))).valid());
        TemplateNoteRevision unknown = DomainFixture.note(TemplateKind.OBGYN, 99,
                DomainFixture.syntheticValues(TemplateKind.OBGYN));
        assertEquals(404, DomainFixture.status(() -> fixture.service.validate(fixture.clinicianA, unknown)));
        assertEquals(404, DomainFixture.status(
                () -> fixture.service.saveNote(fixture.clinicianA, "NOTE-UNKNOWN", unknown)));
    }

    /** Every field the governed version marks required must be present to save the note. */
    @Test
    void refusesNoteMissingARequiredField() {
        DomainFixture fixture = new DomainFixture();
        fixture.approveBaseline(DomainFixture.ORG_A, fixture.adminA, fixture.governorA, TemplateKind.OPERATIVE);
        Map<String, String> partial = DomainFixture.syntheticValues(TemplateKind.OPERATIVE);
        partial.remove("findings");
        TemplateNoteRevision incomplete = DomainFixture.note(TemplateKind.OPERATIVE, 1, partial);

        ValidationReport report = fixture.service.validate(fixture.clinicianA, incomplete);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("missing:findings"), report.violations().toString());
        assertEquals(422, DomainFixture.status(
                () -> fixture.service.saveNote(fixture.clinicianA, "NOTE-INCOMPLETE", incomplete)));
    }

    /**
     * Synthetic domain fixture backed by the in-memory template store.
     *
     * <p>It keeps the five families provable at the domain boundary with
     * deterministic synthetic values, including note saving, dictionary
     * governance and required-field checks that the integration suite above
     * does not assert. The in-memory store is a test double; the production
     * store is the JDBC adapter exercised by the integration tests.</p>
     */
    private static final class DomainFixture {
        private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
        private static final String ORG_B = "22222222-2222-2222-2222-222222222222";

        /** The field names the clause names for each of the five template families. */
        private static final Map<TemplateKind, List<String>> CLAUSE_FIELDS = Map.of(
                TemplateKind.SOAP, List.of("chiefConcern", "history", "exam", "assessment", "plan"),
                TemplateKind.OPERATIVE,
                List.of("procedure", "procedureDate", "team", "findings", "complications", "followUp"),
                TemplateKind.OBGYN, List.of("lmp", "obstetricHistory"),
                TemplateKind.PERINATAL, List.of("birthHistory", "neonatalHistory"),
                TemplateKind.PEDIATRIC_DEVELOPMENT, List.of("milestoneDomain", "observation", "date"));

        private final InMemoryTemplateStore store = new InMemoryTemplateStore();
        private final TemplateService service = new TemplateService(store);
        private final TemplateActor adminA = TemplateActor.of(ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
        private final TemplateActor governorA = TemplateActor.of(ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
        private final TemplateActor clinicianA = TemplateActor.of(ORG_A, "clin-a", TemplateService.CLINICIAN);
        private final TemplateActor clinicianB = TemplateActor.of(ORG_B, "clin-b", TemplateService.CLINICIAN);

        /** Define the approved site dictionaries and approve baseline version one of one family. */
        void approveBaseline(String organizationId, TemplateActor admin, TemplateActor governor,
                             TemplateKind kind) {
            Set<String> defined = new HashSet<>();
            for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
                if (field.type() == FieldType.CODE && defined.add(field.dictionaryKey())) {
                    List<String> entries = SITE_DICTIONARIES.get(field.dictionaryKey());
                    assertNotNull(entries, "no synthetic dictionary for " + field.dictionaryKey());
                    service.defineDictionary(admin, field.dictionaryKey(), entries);
                    service.approveDictionary(governor, field.dictionaryKey());
                }
            }
            service.defineVersion(admin, kind, BaselineTemplates.fieldsFor(kind));
            service.approveVersion(governor, kind, 1);
            assertNotNull(store.version(organizationId, kind, 1).orElse(null));
        }

        /** One valid synthetic value per baseline field, by value kind. */
        static Map<String, String> syntheticValues(TemplateKind kind) {
            Map<String, String> values = new HashMap<>();
            for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
                String value = switch (field.type()) {
                    case DATE -> "2026-10-02";
                    case CODE -> SITE_DICTIONARIES.get(field.dictionaryKey()).get(0);
                    case FREE_TEXT -> "synthetic " + field.key();
                    case TEXT -> "synthetic " + field.key();
                };
                values.put(field.key(), value);
            }
            return values;
        }

        /** Bind synthetic values to the exact baseline fields, carrying a language on free text. */
        static TemplateNoteRevision note(TemplateKind kind, int version, Map<String, String> values) {
            List<FieldValue> authored = new ArrayList<>();
            for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
                String value = values.get(field.key());
                if (value != null) {
                    authored.add(new FieldValue(field.key(), value,
                            field.type() == FieldType.FREE_TEXT ? "en" : null));
                }
            }
            return new TemplateNoteRevision(kind, version, "PAT-001", "clin-a", authored);
        }

        /** Assert the domain status of a refused command. */
        static int status(ThrowingRunnable action) {
            ApiException exception = assertThrows(ApiException.class, action::run);
            assertNotNull(exception.code());
            return exception.status();
        }

        /** A command that may throw the domain exception under test. */
        @FunctionalInterface
        interface ThrowingRunnable {
            void run();
        }
    }
}
