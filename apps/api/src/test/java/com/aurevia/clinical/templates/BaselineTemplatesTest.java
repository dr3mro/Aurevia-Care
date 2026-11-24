package com.aurevia.clinical.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Fast-tier contract between the versioned CLN-04 baselines in Java and the
 * durable baseline catalog in migration V87 (CLN-04).
 *
 * <p>The migration is the authoritative, governance-approved configuration: it
 * carries each family's field meanings (label, type, requiredness, dictionary
 * reference and language) and the approved site dictionaries. If the two drift,
 * a tenant that seeds version one from the catalog would not validate the same
 * notes as the in-process baseline used by the acceptance tests. All values
 * below are synthetic definitions, never patient data.</p>
 */
class BaselineTemplatesTest {

    private static final Path MIGRATION =
            Path.of("../../db/migrations/V87__baseline_templates.sql");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<FieldDefinition>> FIELDS = new TypeReference<>() { };
    private static final TypeReference<Map<String, List<String>>> DICTIONARIES = new TypeReference<>() { };

    private static final Pattern BASELINE_ROW = Pattern.compile(
            "\\(\\s*'([A-Z_]+)'\\s*,\\s*(\\d+)\\s*,\\s*'(\\[[^']*\\])'\\s*::jsonb"
                    + "\\s*,\\s*'(\\{[^']*\\})'\\s*::jsonb",
            Pattern.DOTALL);

    private record Baseline(int version, List<FieldDefinition> fields,
                            Map<String, List<String>> dictionaries) {
    }

    private static Map<String, Baseline> migrationBaselines() throws Exception {
        String sql = Files.readString(MIGRATION);
        Map<String, Baseline> found = new HashMap<>();
        Matcher rows = BASELINE_ROW.matcher(sql);
        while (rows.find()) {
            String family = rows.group(1);
            int version = Integer.parseInt(rows.group(2));
            List<FieldDefinition> fields = JSON.readValue(rows.group(3), FIELDS);
            Map<String, List<String>> dictionaries = JSON.readValue(rows.group(4), DICTIONARIES);
            Baseline previous = found.put(family, new Baseline(version, fields, dictionaries));
            assertNull(previous, "duplicate baseline rows for " + family);
        }
        return found;
    }

    @Test
    void migrationCarriesTheApprovedBaselineForEveryFamily() throws Exception {
        Map<String, Baseline> baselines = migrationBaselines();
        assertEquals(5, baselines.size(), "all five CLN-04 families need a durable baseline");

        for (TemplateKind kind : TemplateKind.values()) {
            Baseline baseline = baselines.get(kind.name());
            assertNotNull(baseline, "missing baseline for " + kind);
            assertEquals(1, baseline.version(), kind + " must start at baseline version one");
            assertEquals(BaselineTemplates.fieldsFor(kind), baseline.fields(),
                    kind + " migration fields must match the in-process baseline definitions");
        }
    }

    @Test
    void migrationBaselinesCarryTheirOwnDictionariesAndRequiredness() throws Exception {
        Map<String, Baseline> baselines = migrationBaselines();
        Set<String> referencedDictionaries = new HashSet<>();

        for (TemplateKind kind : TemplateKind.values()) {
            List<FieldDefinition> fields = baselines.get(kind.name()).fields();
            Set<String> keys = new HashSet<>();
            for (FieldDefinition field : fields) {
                assertTrue(keys.add(field.key()), kind + " repeats field key " + field.key());
                assertFalse(field.label().isBlank(), kind + " has a blank field label");
                assertTrue(field.required(), kind + " baseline fields are governance-required");
                if (field.type() == FieldType.FREE_TEXT) {
                    assertFalse(field.language() == null || field.language().isBlank(),
                            kind + " free text " + field.key() + " must carry a language");
                }
                if (field.type() == FieldType.CODE) {
                    assertNotNull(field.dictionaryKey(), kind + " CODE field needs a dictionary");
                    referencedDictionaries.add(field.dictionaryKey());
                }
            }
        }

        assertTrue(referencedDictionaries.contains("obstetric_history"));
        assertTrue(referencedDictionaries.contains("milestone_domain"));
        Map<String, List<String>> shipped = new HashMap<>();
        for (Baseline baseline : baselines.values()) {
            shipped.putAll(baseline.dictionaries());
        }
        for (String key : referencedDictionaries) {
            assertTrue(shipped.containsKey(key),
                    "the baseline catalog must ship the site dictionary " + key
                            + " so a CODE field can be approved without an out-of-band insert");
            assertFalse(shipped.get(key).isEmpty(), key + " has no entries");
        }
    }

    @Test
    void migrationKeepsBaselinesAppendOnlyAndReadOnlyToTheApplication() throws Exception {
        String sql = Files.readString(MIGRATION);
        assertTrue(sql.contains("Change: expand"), "the migration must be additive");
        assertTrue(sql.contains("CREATE FUNCTION public.reject_baseline_catalog_mutation()"),
                "a global catalog needs its own immutability function (the shared one reads organization_id)");
        assertTrue(sql.contains("FOR EACH ROW EXECUTE FUNCTION public.reject_baseline_catalog_mutation()"),
                "baseline field meanings are immutable");
        assertTrue(sql.contains("GRANT SELECT ON public.clinical_template_baseline TO aurevia_app"),
                "the application may only read the approved baseline");
        assertFalse(sql.contains("UPDATE public.clinical_template_baseline"),
                "there is no application update path for an approved baseline");
    }

    @Test
    void theFourStoryFamiliesExposeTheirNamedFields() {
        Map<TemplateKind, List<String>> expected = new HashMap<>();
        expected.put(TemplateKind.OPERATIVE,
                List.of("procedure", "procedureDate", "team", "findings", "complications", "followUp"));
        expected.put(TemplateKind.OBGYN, List.of("lmp", "obstetricHistory"));
        expected.put(TemplateKind.PERINATAL, List.of("birthHistory", "neonatalHistory"));
        expected.put(TemplateKind.PEDIATRIC_DEVELOPMENT, List.of("milestoneDomain", "observation", "date"));

        for (Map.Entry<TemplateKind, List<String>> entry : expected.entrySet()) {
            List<String> actual = new ArrayList<>();
            for (FieldDefinition field : BaselineTemplates.fieldsFor(entry.getKey())) {
                actual.add(field.key());
            }
            assertEquals(entry.getValue(), actual, entry.getKey() + " field set");
        }
    }
}
