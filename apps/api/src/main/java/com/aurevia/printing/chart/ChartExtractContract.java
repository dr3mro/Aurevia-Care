package com.aurevia.printing.chart;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.aurevia.api.ApiException;
import com.aurevia.chart.record.ChartEntry;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.exchange.release.ReleaseState;
import com.aurevia.printing.HandoutContract.Handout;

/**
 * Value types for the printable consolidated chart extract (CLN-03, CLN-05, CLN-07).
 *
 * <p>An extract is a patient-facing projection of one patient's chart over a
 * visit or a date range. The caller supplies immutable source facts (structured
 * chart entries, signed-note projections and result projections); the service
 * applies the release rules, honours the clinician's section choice and binds
 * the exact selection to an immutable handout version. No value here carries a
 * real patient identifier in normal use: every fixture is synthetic.</p>
 */
public final class ChartExtractContract {

    private ChartExtractContract() {
    }

    /**
     * A selectable section of the consolidated chart extract (CLN-03, CLN-05, CLN-07).
     *
     * <p>The wire key is stable; the English and Arabic titles are the approved
     * patient-facing section headers. Layout direction is chosen centrally from
     * the requested language, not by an individual section.</p>
     */
    public enum ChartSection {
        /** Patient identity and demographics. */
        DEMOGRAPHICS("demographics", "Demographics", "البيانات الشخصية"),
        /** Allergies, including explicit none and unknown assertions. */
        ALLERGIES("allergies", "Allergies", "الحساسية"),
        /** Problems whose effective period is still active. */
        PROBLEMS("problems", "Active problems", "المشاكل النشطة"),
        /** Medications whose effective period is still active. */
        MEDICATIONS("medications", "Medications", "الأدوية"),
        /** Measured observations with a value, unit and measurement time. */
        VITALS("vitals", "Vitals", "القياسات الحيوية"),
        /** Signature-bearing clinical notes only. */
        NOTES("notes", "Signed notes", "الملاحظات الموقعة"),
        /** Results whose patient release is currently visible. */
        RESULTS("results", "Released results", "النتائج المُصدرَة");

        private final String wire;
        private final String englishTitle;
        private final String arabicTitle;

        ChartSection(String wire, String englishTitle, String arabicTitle) {
            this.wire = wire;
            this.englishTitle = englishTitle;
            this.arabicTitle = arabicTitle;
        }

        /**
         * Return the stable wire key of this section.
         *
         * @return lowercase wire key
         */
        public String wire() {
            return wire;
        }

        /**
         * Return the approved section header in one supported language.
         *
         * @param language {@code en} or {@code ar}
         * @return localized section header
         */
        public String title(String language) {
            return "ar".equals(language) ? arabicTitle : englishTitle;
        }
    }

    /**
     * Why a source fact was deliberately left out of an extract (CLN-05).
     *
     * <p>The reason is stored as a stable key and carries an approved localized
     * label so a print preview can explain the omission without exposing the
     * withheld clinical content.</p>
     */
    public enum ExclusionReason {
        /** A note revision without a clinical signature. */
        UNSIGNED_DRAFT("unsigned_draft", "Unsigned draft", "مسودة غير موقعة"),
        /** A result whose patient release is withheld, withdrawn or not yet effective. */
        WITHHELD_RESULT("withheld_result", "Withheld result", "نتيجة محجوبة"),
        /** A note revision retracted as entered in error; never a patient handout. */
        ENTERED_IN_ERROR("entered_in_error", "Entered in error", "أُدخلت بالخطأ");

        private final String wire;
        private final String englishTitle;
        private final String arabicTitle;

        ExclusionReason(String wire, String englishTitle, String arabicTitle) {
            this.wire = wire;
            this.englishTitle = englishTitle;
            this.arabicTitle = arabicTitle;
        }

        /**
         * Return the stable wire key of this reason.
         *
         * @return lowercase wire key
         */
        public String wire() {
            return wire;
        }

        /**
         * Return the approved exclusion label in one supported language.
         *
         * @param language {@code en} or {@code ar}
         * @return localized exclusion label
         */
        public String title(String language) {
            return "ar".equals(language) ? arabicTitle : englishTitle;
        }
    }

    /**
     * The clinician's choice of sections to include, recorded with the version.
     *
     * @param included sections to print, never empty
     * @return this immutable selection
     */
    public record SectionSelection(Set<ChartSection> included) {

        /**
         * Validate and freeze the chosen sections.
         *
         * @throws ApiException with 422 when no section is chosen
         */
        public SectionSelection {
            if (included == null || included.isEmpty()) {
                throw new ApiException(422, "NO_SECTIONS", "at least one chart section is required");
            }
            included = Set.copyOf(included);
        }

        /**
         * Whether a section was chosen.
         *
         * @param section candidate section
         * @return {@code true} when the section will be printed
         */
        public boolean includes(ChartSection section) {
            return included.contains(section);
        }

        /**
         * Return an independent copy of the chosen sections.
         *
         * @return immutable section set
         */
        @Override
        public Set<ChartSection> included() {
            return Set.copyOf(included);
        }

        /**
         * Select every supported section.
         *
         * @return the full section selection
         */
        public static SectionSelection all() {
            return new SectionSelection(Set.of(ChartSection.values()));
        }

        /**
         * Select exactly the supplied sections.
         *
         * @param sections sections to include
         * @return the requested selection
         */
        public static SectionSelection of(ChartSection... sections) {
            return new SectionSelection(Set.of(sections));
        }
    }

    /**
     * The patient and visit context for one extract.
     *
     * @param patientId         handout patient identifier
     * @param patientName       patient name in the handout language
     * @param patientIdentifier external chart identifier shown to the patient
     * @param dateOfBirth       patient date of birth as text
     * @param clinician         responsible clinician
     * @param facility          issuing facility
     * @return this immutable value
     */
    public record ChartDemographics(String patientId, String patientName, String patientIdentifier,
                                    String dateOfBirth, String clinician, String facility) {
    }

    /**
     * The interval an extract covers: one visit or one inclusive date range.
     *
     * @param visitId encounter identifier when this is a visit scope, otherwise {@code null}
     * @param from    inclusive range start when this is a range scope, otherwise {@code null}
     * @param to      inclusive range end when this is a range scope, otherwise {@code null}
     * @return this immutable scope
     */
    public record ExtractScope(String visitId, Instant from, Instant to) {

        /**
         * Validate that exactly one of a visit and an ordered range is present.
         *
         * @throws ApiException with 422 for a malformed scope
         */
        public ExtractScope {
            if (visitId == null && (from == null || to == null)) {
                throw new ApiException(422, "INVALID_SCOPE", "a range needs both bounds");
            }
            if (visitId != null && (from != null || to != null)) {
                throw new ApiException(422, "INVALID_SCOPE", "a scope is either a visit or a range");
            }
            if (from != null && from.isAfter(to)) {
                throw new ApiException(422, "INVALID_SCOPE", "the range start must not follow its end");
            }
        }

        /**
         * Scope an extract to one visit.
         *
         * @param visitId encounter identifier
         * @return a visit scope
         */
        public static ExtractScope visit(String visitId) {
            return new ExtractScope(visitId, null, null);
        }

        /**
         * Scope an extract to an inclusive time range.
         *
         * @param from inclusive start
         * @param to   inclusive end
         * @return a range scope
         */
        public static ExtractScope range(Instant from, Instant to) {
            return new ExtractScope(null, from, to);
        }

        /**
         * Whether this scope selects a single visit.
         *
         * @return {@code true} for a visit scope
         */
        public boolean isVisit() {
            return visitId != null;
        }

        /**
         * Whether one fact falls inside this scope.
         *
         * @param encounterId encounter identifier carried by the fact
         * @param clinicalAt  clinical instant carried by the fact
         * @return {@code true} when the fact is in scope
         */
        public boolean contains(String encounterId, Instant clinicalAt) {
            if (visitId != null) {
                return visitId.equals(encounterId);
            }
            return !clinicalAt.isBefore(from) && !clinicalAt.isAfter(to);
        }

        /**
         * Return a deterministic printable description of this scope.
         *
         * @return stable scope text used in the extract content and checksum
         */
        public String wire() {
            return isVisit() ? "visit:" + visitId : "range:" + from + ".." + to;
        }
    }

    /**
     * A clinical note projection for the extract; the owning note revision stays authoritative.
     *
     * @param id          opaque note identifier
     * @param title       note title
     * @param content     clinical content
     * @param state       authoritative note lifecycle state
     * @param encounterId optional encounter context
     * @param effectiveAt clinical effective instant
     * @param signer      attesting signer, or {@code null} on a draft
     * @return this immutable projection
     */
    public record ChartNote(String id, String title, String content, NoteState state,
                            String encounterId, Instant effectiveAt, String signer) {
    }

    /**
     * A result projection for the extract; the owning result stays authoritative.
     *
     * @param id           opaque result identifier
     * @param title        result title
     * @param content      released result text
     * @param releaseState authoritative patient release state
     * @param encounterId  optional encounter context
     * @param effectiveAt  instant from which the release is visible
     * @return this immutable projection
     */
    public record ChartResult(String id, String title, String content, ReleaseState releaseState,
                              String encounterId, Instant effectiveAt) {
    }

    /**
     * One fact deliberately left out of the extract, with a non-sensitive reason.
     *
     * @param section section the fact belonged to
     * @param itemId  opaque identifier of the omitted fact
     * @param reason  why the fact was omitted
     * @return this immutable value
     */
    public record ExcludedItem(ChartSection section, String itemId, ExclusionReason reason) {
    }

    /**
     * One rendered extract section and its patient-facing lines in display order.
     *
     * @param section section header
     * @param lines   content lines in display order
     * @return this immutable view
     */
    public record ExtractSectionView(ChartSection section, List<String> lines) {

        /**
         * Freeze the supplied lines on construction.
         */
        public ExtractSectionView {
            lines = List.copyOf(lines);
        }

        /**
         * Return an independent copy of the content lines.
         *
         * @return immutable lines
         */
        @Override
        public List<String> lines() {
            return List.copyOf(lines);
        }
    }

    /**
     * The immutable printable extracted chart and the metadata of its bound handout.
     *
     * <p>The handout version and checksum bind the printed PDF to the exact
     * section selection and the exact source facts used here. A different
     * selection or a changed source produces a new version; a repeat of the
     * same extract reuses the original bytes.</p>
     *
     * @param handout   immutable versioned handout metadata
     * @param pdf       rendered A4 PDF bytes
     * @param selection section choice recorded with this version
     * @param sections  rendered sections in display order
     * @param excluded  deliberately omitted facts with reasons
     * @return this immutable extract
     */
    public record ChartExtract(Handout handout, byte[] pdf, SectionSelection selection,
                               List<ExtractSectionView> sections, List<ExcludedItem> excluded) {

        /**
         * Freeze the rendered bytes and the lists on construction.
         */
        public ChartExtract {
            pdf = Arrays.copyOf(pdf, pdf.length);
            sections = List.copyOf(sections);
            excluded = List.copyOf(excluded);
        }

        /**
         * Return an independent copy of the rendered bytes.
         *
         * @return PDF bytes
         */
        @Override
        public byte[] pdf() {
            return Arrays.copyOf(pdf, pdf.length);
        }

        /**
         * Return an independent copy of the rendered sections.
         *
         * @return immutable sections
         */
        @Override
        public List<ExtractSectionView> sections() {
            return List.copyOf(sections);
        }

        /**
         * Return an independent copy of the omitted facts.
         *
         * @return immutable exclusions
         */
        @Override
        public List<ExcludedItem> excluded() {
            return List.copyOf(excluded);
        }
    }

    /**
     * Command carrying the tenant, viewer, scope, section choice and source facts.
     *
     * @param organizationId active tenant, derived from the request context
     * @param actor          authenticated caller, never inferred from the body
     * @param language       {@code en} or {@code ar}
     * @param scope          visit or date range selector
     * @param selection      clinician's section choice
     * @param demographics   patient and visit context
     * @param entries        structured chart entries
     * @param notes          clinical note projections
     * @param results        result projections
     * @return this immutable command
     */
    public record ChartExtractRequest(String organizationId, Actor actor, String language,
                                      ExtractScope scope, SectionSelection selection,
                                      ChartDemographics demographics, List<ChartEntry> entries,
                                      List<ChartNote> notes, List<ChartResult> results) {

        /**
         * Freeze the supplied source lists on construction.
         */
        public ChartExtractRequest {
            entries = List.copyOf(entries);
            notes = List.copyOf(notes);
            results = List.copyOf(results);
        }

        /**
         * Return an independent copy of the chart entries.
         *
         * @return immutable entries
         */
        @Override
        public List<ChartEntry> entries() {
            return List.copyOf(entries);
        }

        /**
         * Return an independent copy of the note projections.
         *
         * @return immutable notes
         */
        @Override
        public List<ChartNote> notes() {
            return List.copyOf(notes);
        }

        /**
         * Return an independent copy of the result projections.
         *
         * @return immutable results
         */
        @Override
        public List<ChartResult> results() {
            return List.copyOf(results);
        }
    }
}
