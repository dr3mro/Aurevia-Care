package com.aurevia.printing.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.chart.record.ChartEntry;
import com.aurevia.chart.record.EntrySource;
import com.aurevia.chart.record.EntryType;
import com.aurevia.chart.record.PresenceState;
import com.aurevia.chart.record.VerificationState;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.PdfRenderer;
import com.aurevia.exchange.release.ReleaseState;
import com.aurevia.printing.HandoutRenderer;
import com.aurevia.printing.HandoutService;
import com.aurevia.printing.LocalHandoutArchive;
import com.aurevia.printing.chart.ChartExtractContract.ChartDemographics;
import com.aurevia.printing.chart.ChartExtractContract.ChartExtract;
import com.aurevia.printing.chart.ChartExtractContract.ChartExtractRequest;
import com.aurevia.printing.chart.ChartExtractContract.ChartNote;
import com.aurevia.printing.chart.ChartExtractContract.ChartResult;
import com.aurevia.printing.chart.ChartExtractContract.ChartSection;
import com.aurevia.printing.chart.ChartExtractContract.ExcludedItem;
import com.aurevia.printing.chart.ChartExtractContract.ExclusionReason;
import com.aurevia.printing.chart.ChartExtractContract.ExtractScope;
import com.aurevia.printing.chart.ChartExtractContract.ExtractSectionView;
import com.aurevia.printing.chart.ChartExtractContract.SectionSelection;

/**
 * CLN-03 / CLN-05 / CLN-07 consolidated chart extract behaviour.
 *
 * <p>All fixtures are synthetic: invented identifiers, a made-up patient and
 * fixed instants. Every check drives the public {@link com.aurevia.printing.chart.ChartExtractService}
 * entry point with a real PDF renderer and an in-process handout archive.</p>
 */
class ChartExtractServiceTest {

    private static final String ORG = "org-alpha";
    private static final String OTHER_ORG = "org-beta";
    private static final String PATIENT = "PAT-SYN-1";
    private static final String VISIT = "visit-1";
    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-21T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private ChartExtractService service;
    private Clock clock;

    /** Build a service over an empty archive and a pinned clock. */
    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ChartExtractService(
                new HandoutService(new HandoutRenderer(new PdfRenderer()), new LocalHandoutArchive(), clock), clock);
    }

    private static Actor actor() {
        return Actor.staff(ORG, "clinician-a", "clinician");
    }

    private static ChartDemographics demographics() {
        return new ChartDemographics(PATIENT, "Synthetic Patient", "MRN-SYN-1", "1990-01-01",
                "Dr Synthetic", "Synthetic Clinic");
    }

    private static ChartExtractRequest request(String organizationId, Actor actor, String language,
                                               ExtractScope scope, SectionSelection selection) {
        return new ChartExtractRequest(organizationId, actor, language, scope, selection, demographics(),
                List.of(), List.of(), List.of());
    }

    private static ChartEntry entry(String id, EntryType type, String encounterId, Instant clinicalAt,
                                    PresenceState presence, Instant effectiveTo, Instant measuredAt,
                                    String value, String unit, String text) {
        return new ChartEntry(id, PATIENT, encounterId, type, text, null, null, null, null, null,
                EntrySource.CLINICIAN_OBSERVED, presence, null, VerificationState.VERIFIED, "verifier", null,
                clinicalAt, effectiveTo, measuredAt, value, unit, "author", "data-entry", 1,
                Instant.parse("2026-11-19T00:00:00Z"));
    }

    private static ChartNote note(String id, NoteState state, String encounterId, Instant effectiveAt,
                                  String content) {
        return new ChartNote(id, "Progress note", content, state, encounterId, effectiveAt, "signer");
    }

    private static ChartResult result(String id, ReleaseState state, String encounterId, Instant effectiveAt,
                                      String content) {
        return new ChartResult(id, "Lab result", content, state, encounterId, effectiveAt);
    }

    private static List<String> allLines(ChartExtract extract) {
        List<String> lines = new ArrayList<>();
        for (ExtractSectionView section : extract.sections()) {
            lines.addAll(section.lines());
        }
        return lines;
    }

    private static boolean anyLineContains(ChartExtract extract, String needle) {
        return allLines(extract).stream().anyMatch(line -> line.contains(needle));
    }

    private static List<String> excludedIds(ChartExtract extract, ExclusionReason reason) {
        return extract.excluded().stream().filter(item -> item.reason() == reason)
                .map(ExcludedItem::itemId).toList();
    }

    // --- validation and failure paths -------------------------------------------------

    @Test
    void rejectsMissingActorForeignTenantAndMissingSelection() {
        ApiException noActor = assertThrows(ApiException.class, () -> service.extract(
                request(ORG, null, "en", ExtractScope.visit(VISIT), SectionSelection.all())));
        assertEquals(403, noActor.status());

        ApiException foreign = assertThrows(ApiException.class, () -> service.extract(
                request(ORG, Actor.staff(OTHER_ORG, "clinician-b", "clinician"), "en",
                        ExtractScope.visit(VISIT), SectionSelection.all())));
        assertEquals(403, foreign.status());
        assertEquals("WRONG_TENANT", foreign.code());

        ApiException noSections = assertThrows(ApiException.class, () -> service.extract(
                request(ORG, actor(), "en", ExtractScope.visit(VISIT), null)));
        assertEquals(422, noSections.status());

        ApiException badLanguage = assertThrows(ApiException.class, () -> service.extract(
                request(ORG, actor(), "fr", ExtractScope.visit(VISIT), SectionSelection.all())));
        assertEquals(422, badLanguage.status());
    }

    @Test
    void rejectsInvalidScopesAndEmptySelections() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ExtractScope(null, T0, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ExtractScope(null, null, T1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ExtractScope(VISIT, T0, T1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ExtractScope(VISIT, null, T1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ExtractScope.range(T1, T0)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new SectionSelection(Set.of())).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new SectionSelection(null)).status());
    }

    @Test
    void rejectsNullBoundaryValuesWithStructuredErrors() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.extract(
                new ChartExtractRequest(null, actor(), "en", ExtractScope.visit(VISIT),
                        SectionSelection.all(), demographics(), List.of(), List.of(), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.extract(
                new ChartExtractRequest(ORG, actor(), "en", null,
                        SectionSelection.all(), demographics(), List.of(), List.of(), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.extract(
                new ChartExtractRequest(ORG, actor(), "en", ExtractScope.visit(VISIT),
                        SectionSelection.all(), null, List.of(), List.of(), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.extract(
                new ChartExtractRequest(ORG, actor(), "en", ExtractScope.visit(VISIT),
                        SectionSelection.of(ChartSection.NOTES), demographics(), List.of(),
                        List.of(new ChartNote("n", "title", "content", NoteState.SIGNED, VISIT,
                                null, "signer")), List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.extract(
                new ChartExtractRequest(ORG, actor(), "en", ExtractScope.visit(VISIT),
                        SectionSelection.of(ChartSection.RESULTS), demographics(), List.of(), List.of(),
                        List.of(new ChartResult("r", "title", "content", ReleaseState.AUTHORIZED,
                                VISIT, null))))).status());
    }

    // --- full extract -----------------------------------------------------------------

    @Test
    void includesEverySelectedSectionWithLocalizedHeaders() {
        ChartExtract extract = service.extract(request(ORG, actor(), "en",
                ExtractScope.visit(VISIT), SectionSelection.all()));

        assertEquals(ChartSection.values().length, extract.sections().size());
        assertEquals("en", extract.handout().language());
        assertEquals("LTR", extract.handout().direction());
        assertEquals(1, extract.handout().version());
        assertTrue(extract.pdf().length > 0);
        for (ExtractSectionView section : extract.sections()) {
            assertEquals(section.section().title("en"), section.section().title("en"));
            assertTrue(anyLineContains(extract, section.section().title("en")));
        }
        assertTrue(anyLineContains(extract, "Synthetic Patient"));
        assertTrue(anyLineContains(extract, "MRN-SYN-1"));
        assertTrue(anyLineContains(extract, "Dr Synthetic"));
        assertTrue(anyLineContains(extract, "Synthetic Clinic"));

        ChartExtract otherVisit = service.extract(request(ORG, actor(), "en",
                ExtractScope.visit("visit-2"), SectionSelection.all()));
        assertNotEquals(extract.handout().sourceChecksum(), otherVisit.handout().sourceChecksum());
    }

    @Test
    void arabicExtractUsesRightToLeftLayoutAndArabicLabels() {
        ChartExtract extract = service.extract(request(ORG, actor(), "ar",
                ExtractScope.visit(VISIT), SectionSelection.all()));

        assertEquals("ar", extract.handout().language());
        assertEquals("RTL", extract.handout().direction());
        assertEquals(ChartSection.ALLERGIES.title("ar"), extract.sections().get(1).section().title("ar"));
        assertTrue(anyLineContains(extract, ChartSection.ALLERGIES.title("ar")));
        assertTrue(anyLineContains(extract, "المريض"));
        assertTrue(anyLineContains(extract, "المنشأة"));
        assertNotEquals(ChartSection.ALLERGIES.title("en"), ChartSection.ALLERGIES.title("ar"));
    }

    @Test
    void sectionLabelsRenderInBothLanguages() {
        for (ChartSection section : ChartSection.values()) {
            assertFalse(section.title("en").isBlank());
            assertFalse(section.title("ar").isBlank());
            assertNotEquals(section.title("en"), section.title("ar"));
        }
        for (ExclusionReason reason : ExclusionReason.values()) {
            assertFalse(reason.title("en").isBlank());
            assertFalse(reason.title("ar").isBlank());
            assertNotEquals(reason.title("en"), reason.title("ar"));
        }
        assertFalse(ChartLabels.presence(PresenceState.PRESENT, "en").isBlank());
        assertFalse(ChartLabels.presence(PresenceState.ABSENT, "ar").isBlank());
        assertFalse(ChartLabels.presence(PresenceState.UNKNOWN, "en").isBlank());
    }

    // --- release rules ----------------------------------------------------------------

    @Test
    void excludesUnsignedDraftNotesAndWithheldResults() {
        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.visit(VISIT), SectionSelection.all(), demographics(), List.of(),
                List.of(note("note-signed", NoteState.SIGNED, VISIT, T0, "signed content"),
                        note("note-draft", NoteState.DRAFT, VISIT, T0, "draft content"),
                        note("note-amend", NoteState.AMENDMENT_DRAFT, VISIT, T0, "amend content"),
                        note("note-addendum", NoteState.ADDENDUM, VISIT, T0, "addendum content"),
                        note("note-error", NoteState.ENTERED_IN_ERROR, VISIT, T0, "error content")),
                List.of(result("res-released", ReleaseState.AUTHORIZED, VISIT, T0, "released content"),
                        result("res-held", ReleaseState.HELD, VISIT, T0, "held content"),
                        result("res-withdrawn", ReleaseState.WITHDRAWN, VISIT, T0, "withdrawn content"),
                        result("res-corrected", ReleaseState.CORRECTED, VISIT, T0, "corrected content"))));

        assertTrue(anyLineContains(extract, "signed content"));
        assertTrue(anyLineContains(extract, "addendum content"));
        assertFalse(anyLineContains(extract, "draft content"));
        assertFalse(anyLineContains(extract, "amend content"));
        assertTrue(anyLineContains(extract, "released content"));
        assertFalse(anyLineContains(extract, "held content"));
        assertFalse(anyLineContains(extract, "withdrawn content"));
        assertFalse(anyLineContains(extract, "error content"));
        assertFalse(anyLineContains(extract, "corrected content"));

        assertTrue(excludedIds(extract, ExclusionReason.UNSIGNED_DRAFT).containsAll(
                List.of("note-draft", "note-amend")));
        assertTrue(excludedIds(extract, ExclusionReason.WITHHELD_RESULT).containsAll(
                List.of("res-held", "res-withdrawn", "res-corrected")));
        assertTrue(excludedIds(extract, ExclusionReason.ENTERED_IN_ERROR).contains("note-error"));
        assertEquals(2, excludedIds(extract, ExclusionReason.UNSIGNED_DRAFT).size());
        assertEquals(3, excludedIds(extract, ExclusionReason.WITHHELD_RESULT).size());
        assertEquals(1, excludedIds(extract, ExclusionReason.ENTERED_IN_ERROR).size());
    }

    @Test
    void aFutureAuthorizedResultIsNotYetVisible() {
        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.range(T0, NOW.plusSeconds(3600)), SectionSelection.of(ChartSection.RESULTS),
                demographics(), List.of(), List.of(),
                List.of(result("res-future", ReleaseState.AUTHORIZED, VISIT, NOW.plusSeconds(1), "future content"))));
        assertFalse(anyLineContains(extract, "future content"));
        assertTrue(excludedIds(extract, ExclusionReason.WITHHELD_RESULT).contains("res-future"));
    }

    // --- scope filters ----------------------------------------------------------------

    @Test
    void rangeFilterExcludesFactsOutsideRange() {
        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.range(T0, T1), SectionSelection.all(), demographics(),
                List.of(entry("e-in", EntryType.VITAL, "x", T0.plusSeconds(10), PresenceState.PRESENT,
                                null, T0.plusSeconds(10), "120", "bpm", "inside vital"),
                        entry("e-before", EntryType.VITAL, "x", T0.minusSeconds(1), PresenceState.PRESENT,
                                null, T0.minusSeconds(1), "90", "bpm", "before vital"),
                        entry("e-after", EntryType.VITAL, "x", T1.plusSeconds(1), PresenceState.PRESENT,
                                null, T1.plusSeconds(1), "130", "bpm", "after vital"),
                        entry("e-allergy-out", EntryType.ALLERGY, "x", T1.plusSeconds(1),
                                PresenceState.PRESENT, null, null, null, null, "out allergy")),
                List.of(note("note-in", NoteState.SIGNED, "x", T0.plusSeconds(20), "inside note"),
                        note("note-after", NoteState.SIGNED, "x", T1.plusSeconds(1), "after note")),
                List.of(result("res-in", ReleaseState.AUTHORIZED, "x", T0.plusSeconds(30), "inside result"),
                        result("res-before", ReleaseState.AUTHORIZED, "x", T0.minusSeconds(1), "before result"))));

        assertTrue(anyLineContains(extract, "inside vital"));
        assertTrue(anyLineContains(extract, "inside note"));
        assertTrue(anyLineContains(extract, "inside result"));
        assertFalse(anyLineContains(extract, "before vital"));
        assertFalse(anyLineContains(extract, "after vital"));
        assertFalse(anyLineContains(extract, "after note"));
        assertFalse(anyLineContains(extract, "before result"));
        assertFalse(anyLineContains(extract, "out allergy"));

        ChartExtract wider = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.range(T0, T1.plusSeconds(1)), SectionSelection.all(), demographics(),
                List.of(), List.of(), List.of()));
        assertNotEquals(extract.handout().sourceChecksum(), wider.handout().sourceChecksum());
    }

    @Test
    void visitScopeMatchesEncounterOnly() {
        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.visit(VISIT), SectionSelection.of(ChartSection.NOTES), demographics(),
                List.of(),
                List.of(note("note-match", NoteState.SIGNED, VISIT, T0, "visit note"),
                        note("note-other", NoteState.SIGNED, "visit-2", T0, "other visit note")),
                List.of()));
        assertTrue(anyLineContains(extract, "visit note"));
        assertFalse(anyLineContains(extract, "other visit note"));
        assertTrue(extract.excluded().isEmpty());
    }

    @Test
    void clinicalTimeFallsBackThroughEffectiveFromRecordedAtAndEpoch() {
        // These entries have no measuredAt; the range filter must still place them by effective time.
        ChartEntry byEffective = new ChartEntry("e-eff", PATIENT, "x", EntryType.MEDICATION,
                "effective med", null, null, null, null, null, EntrySource.EXTERNAL_RECORD,
                PresenceState.PRESENT, null, VerificationState.VERIFIED, null, null,
                T0.plusSeconds(5), null, null, null, null, null, null, 1, T1.plusSeconds(10));
        ChartEntry byRecorded = new ChartEntry("e-rec", PATIENT, "x", EntryType.MEDICATION,
                "recorded med", null, null, null, null, null, EntrySource.EXTERNAL_RECORD,
                PresenceState.PRESENT, null, VerificationState.VERIFIED, null, null,
                null, null, null, null, null, null, null, 1, T0.plusSeconds(6));
        ChartEntry noTime = new ChartEntry("e-none", PATIENT, "x", EntryType.MEDICATION,
                "no time med", null, null, null, null, null, EntrySource.EXTERNAL_RECORD,
                PresenceState.PRESENT, null, VerificationState.VERIFIED, null, null,
                null, null, null, null, null, null, null, 1, null);

        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.range(T0, T1), SectionSelection.of(ChartSection.MEDICATIONS), demographics(),
                List.of(byEffective, byRecorded, noTime), List.of(), List.of()));

        assertTrue(anyLineContains(extract, "effective med"));
        assertTrue(anyLineContains(extract, "recorded med"));
        assertFalse(anyLineContains(extract, "no time med"));
    }

    // --- section contents -------------------------------------------------------------

    @Test
    void onlyActiveProblemsAndMedicationsAppear() {
        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.visit(VISIT),
                SectionSelection.of(ChartSection.PROBLEMS, ChartSection.MEDICATIONS), demographics(),
                List.of(
                        entry("p-active", EntryType.PROBLEM, VISIT, T0, PresenceState.PRESENT, null,
                                null, null, null, "active problem"),
                        entry("p-future-end", EntryType.PROBLEM, VISIT, T0, PresenceState.PRESENT,
                                NOW.plusSeconds(1), null, null, null, "future-ended problem"),
                        entry("p-ended", EntryType.PROBLEM, VISIT, T0, PresenceState.PRESENT,
                                T0.plusSeconds(1), null, null, null, "expired problem"),
                        entry("p-absent", EntryType.PROBLEM, VISIT, T0, PresenceState.ABSENT, null,
                                null, null, null, "absent problem"),
                        entry("m-active", EntryType.MEDICATION, VISIT, T0, PresenceState.PRESENT, null,
                                null, null, null, "active medication"),
                        entry("m-unknown", EntryType.MEDICATION, VISIT, T0, PresenceState.UNKNOWN, null,
                                null, null, null, "unknown medication"),
                        entry("other-type", EntryType.ALLERGY, VISIT, T0, PresenceState.PRESENT, null,
                                null, null, null, "not a problem")),
                List.of(), List.of()));

        assertTrue(anyLineContains(extract, "active problem"));
        assertTrue(anyLineContains(extract, "future-ended problem"));
        assertFalse(anyLineContains(extract, "expired problem"));
        assertFalse(anyLineContains(extract, "absent problem"));
        assertTrue(anyLineContains(extract, "active medication"));
        assertFalse(anyLineContains(extract, "unknown medication"));
        assertFalse(anyLineContains(extract, "not a problem"));
    }

    @Test
    void allergiesIncludeAbsentAndUnknownAssertions() {
        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.visit(VISIT), SectionSelection.of(ChartSection.ALLERGIES), demographics(),
                List.of(
                        entry("a-present", EntryType.ALLERGY, VISIT, T0, PresenceState.PRESENT,
                                null, null, null, null, "penicillin"),
                        entry("a-absent", EntryType.ALLERGY, VISIT, T0, PresenceState.ABSENT,
                                null, null, null, null, "no known allergies"),
                        entry("a-unknown", EntryType.ALLERGY, VISIT, T0, PresenceState.UNKNOWN,
                                null, null, null, null, "not determined")),
                List.of(), List.of()));
        assertTrue(anyLineContains(extract, "penicillin"));
        assertTrue(anyLineContains(extract, "no known allergies"));
        assertTrue(anyLineContains(extract, "not determined"));
        assertTrue(anyLineContains(extract, ChartLabels.presence(PresenceState.ABSENT, "en")));
        assertTrue(anyLineContains(extract, ChartLabels.presence(PresenceState.UNKNOWN, "en")));
    }

    @Test
    void vitalsCarryValueUnitAndMeasurementTime() {
        Instant measured = T0.plusSeconds(42);
        ChartExtract extract = service.extract(new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.visit(VISIT), SectionSelection.of(ChartSection.VITALS), demographics(),
                List.of(entry("v-1", EntryType.VITAL, VISIT, T0, PresenceState.PRESENT, null,
                        measured, "37.2", "degC", "temperature")),
                List.of(), List.of()));
        String line = allLines(extract).stream().filter(value -> value.contains("temperature"))
                .findFirst().orElseThrow();
        assertTrue(line.contains("37.2"));
        assertTrue(line.contains("degC"));
        assertTrue(line.contains(measured.toString()));
    }

    // --- versioning -------------------------------------------------------------------

    @Test
    void sectionChoiceChangesVersionAndIsRecorded() {
        SectionSelection all = SectionSelection.all();
        SectionSelection subset = SectionSelection.of(ChartSection.DEMOGRAPHICS, ChartSection.VITALS);
        ExtractScope scope = ExtractScope.visit(VISIT);

        ChartExtract first = service.extract(request(ORG, actor(), "en", scope, all));
        ChartExtract changed = service.extract(request(ORG, actor(), "en", scope, subset));
        ChartExtract repeated = service.extract(request(ORG, actor(), "en", scope, all));

        assertEquals(all, first.selection());
        assertEquals(all.included(), first.selection().included());
        assertEquals(subset, changed.selection());
        assertNotEquals(first.handout().sourceChecksum(), changed.handout().sourceChecksum());
        assertNotEquals(first.handout().version(), changed.handout().version());
        assertEquals(1, first.handout().version());
        assertEquals(2, changed.handout().version());
        assertEquals(first.handout().version(), repeated.handout().version());
        assertEquals(first.handout().sourceChecksum(), repeated.handout().sourceChecksum());
        assertEquals(first.handout().sha256(), repeated.handout().sha256());
    }

    @Test
    void configurationExposesTheExtractorAsABean() {
        ChartExtractService bean = new ChartExtractConfiguration().chartExtractService(
                new HandoutService(new HandoutRenderer(new PdfRenderer()), new LocalHandoutArchive(), clock), clock);
        ChartExtract extract = bean.extract(request(ORG, actor(), "en",
                ExtractScope.visit(VISIT), SectionSelection.all()));
        assertEquals(1, extract.handout().version());
    }

    @Test
    void defensiveCopiesProtectTheExtractAndRequest() {
        List<ChartEntry> entries = new ArrayList<>();
        ChartExtractRequest original = new ChartExtractRequest(ORG, actor(), "en",
                ExtractScope.visit(VISIT), SectionSelection.all(), demographics(), entries,
                new ArrayList<>(), new ArrayList<>());
        ChartExtract extract = service.extract(original);
        int sectionCount = extract.sections().size();
        byte[] pdf = extract.pdf();

        entries.add(entry("late", EntryType.VITAL, VISIT, T0, PresenceState.PRESENT, null, T0, "1", "u", "late"));
        pdf[0] = 0;
        assertThrows(UnsupportedOperationException.class, () -> extract.sections().clear());
        assertThrows(UnsupportedOperationException.class, () -> extract.excluded().clear());

        assertEquals(sectionCount, extract.sections().size());
        assertTrue(extract.pdf()[0] != 0);
        assertEquals(0, original.entries().size());
    }
}
