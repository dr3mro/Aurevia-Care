package com.aurevia.printing.chart;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.aurevia.api.ApiException;
import com.aurevia.chart.record.ChartEntry;
import com.aurevia.chart.record.EntryType;
import com.aurevia.chart.record.PresenceState;
import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.documents.ArtifactInputs;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.printing.HandoutContract.HandoutRequest;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.printing.HandoutContract.RenderedHandout;
import com.aurevia.printing.HandoutKind;
import com.aurevia.printing.HandoutService;
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
 * Build a printable consolidated chart extract for one visit or date range (CLN-03, CLN-05, CLN-07).
 *
 * <p>The service is a pure domain kernel: the caller gathers the immutable source
 * facts, and this class applies the release rules, honours the clinician's
 * section choice and produces the patient-facing content. Unsigned note drafts
 * and results whose patient release is withheld, withdrawn or not yet effective
 * are never printed; each omission is recorded with a non-sensitive reason for
 * the preview. The exact selection and content are checksummed and bound to an
 * immutable versioned PDF through the existing handout service.</p>
 */
public final class ChartExtractService {

    private static final String SOURCE_SYSTEM = "AUREVIA-CHART";

    private final HandoutService handoutService;
    private final Clock clock;

    /**
     * Create an extractor with its collaborators injected.
     *
     * @param handoutService immutable versioned PDF renderer and archive
     * @param clock          server clock for active windows and release visibility
     */
    public ChartExtractService(HandoutService handoutService, Clock clock) {
        this.handoutService = handoutService;
        this.clock = clock;
    }

    /**
     * Build the printable extract for one patient and scope.
     *
     * @param request tenant, viewer, scope, section choice and immutable source facts
     * @return the rendered extract with its recorded selection and omissions
     * @throws ApiException on a missing actor or tenant, a foreign tenant, a
     *                      missing selector or section choice, or an invalid
     *                      source fact
     */
    public ChartExtract extract(ChartExtractRequest request) {
        Actor actor = request.actor();
        if (actor == null) {
            throw new ApiException(403, "FORBIDDEN", "missing print permission");
        }
        String organizationId = request.organizationId();
        if (organizationId == null) {
            throw new ApiException(422, "INVALID_REQUEST", "a chart extract needs a tenant");
        }
        if (!organizationId.equals(actor.organizationId())) {
            throw new ApiException(403, "WRONG_TENANT", "the chart belongs to another organization");
        }
        String language = ArtifactInputs.language(request.language()).wire();
        SectionSelection selection = request.selection();
        if (selection == null) {
            throw new ApiException(422, "NO_SECTIONS", "a chart extract needs at least one section");
        }
        Instant now = clock.instant();
        ExtractScope scope = request.scope();
        if (scope == null) {
            throw new ApiException(422, "INVALID_REQUEST", "a chart extract needs a visit or range");
        }
        ChartDemographics demographics = request.demographics();
        if (demographics == null) {
            throw new ApiException(422, "INVALID_REQUEST", "a chart extract needs demographics");
        }

        List<String> contentLines = new ArrayList<>();
        List<ExtractSectionView> sections = new ArrayList<>();
        List<ExcludedItem> excluded = new ArrayList<>();
        contentLines.add(ChartLabels.label(scope.isVisit() ? "scopeVisit" : "scopeRange", language)
                + ": " + scope.wire());

        for (ChartSection section : ChartSection.values()) {
            if (!selection.includes(section)) {
                continue;
            }
            List<String> sectionLines = new ArrayList<>();
            sectionLines.add(ChartLabels.label("section", language) + ": " + section.title(language));
            sectionLines.addAll(sectionLines(section, request, scope, demographics,
                    now, language, excluded));
            sections.add(new ExtractSectionView(section, sectionLines));
            contentLines.addAll(sectionLines);
        }

        String checksum = NoteChecksum.sha256(canonical(organizationId, scope, selection, contentLines, excluded));
        String sourceId = sourceId(demographics.patientId(), scope);
        HandoutSource source = new HandoutSource(organizationId, HandoutKind.CHART_SUMMARY, sourceId,
                1, checksum, NoteState.SIGNED.wire(), true, null, demographics.patientId(),
                demographics.patientName(), demographics.clinician(), demographics.facility(), now,
                SOURCE_SYSTEM, contentLines);
        RenderedHandout rendered = handoutService.handout(new HandoutRequest(actor, language, source));
        return new ChartExtract(rendered.metadata(), rendered.content(), selection, sections, excluded);
    }

    private static List<String> sectionLines(ChartSection section, ChartExtractRequest request,
                                             ExtractScope scope, ChartDemographics demographics,
                                             Instant now, String language, List<ExcludedItem> excluded) {
        if (section == ChartSection.DEMOGRAPHICS) {
            return demographicsLines(demographics, language);
        }
        if (section == ChartSection.ALLERGIES) {
            return familyLines(request.entries(), scope, EntryType.ALLERGY, false, now, language);
        }
        if (section == ChartSection.PROBLEMS) {
            return familyLines(request.entries(), scope, EntryType.PROBLEM, true, now, language);
        }
        if (section == ChartSection.MEDICATIONS) {
            return familyLines(request.entries(), scope, EntryType.MEDICATION, true, now, language);
        }
        if (section == ChartSection.VITALS) {
            return vitalLines(request.entries(), scope, language);
        }
        if (section == ChartSection.NOTES) {
            return noteLines(request.notes(), scope, language, excluded);
        }
        return resultLines(request.results(), scope, now, language, excluded);
    }

    private static List<String> demographicsLines(ChartDemographics demographics, String language) {
        return List.of(
                ChartLabels.label("name", language) + ": " + demographics.patientName(),
                ChartLabels.label("patientId", language) + ": " + demographics.patientId(),
                ChartLabels.label("identifier", language) + ": " + demographics.patientIdentifier(),
                ChartLabels.label("dob", language) + ": " + demographics.dateOfBirth(),
                ChartLabels.label("clinician", language) + ": " + demographics.clinician(),
                ChartLabels.label("facility", language) + ": " + demographics.facility());
    }

    private static List<String> familyLines(List<ChartEntry> entries, ExtractScope scope, EntryType type,
                                            boolean activeOnly, Instant now, String language) {
        List<String> lines = new ArrayList<>();
        for (ChartEntry entry : entries) {
            if (entry.type() != type) {
                continue;
            }
            if (activeOnly && !isActive(entry, now)) {
                continue;
            }
            if (!scope.contains(entry.encounterId(), clinicalTime(entry))) {
                continue;
            }
            lines.add(typeLine(entry, language));
        }
        return List.copyOf(lines);
    }

    private static List<String> vitalLines(List<ChartEntry> entries, ExtractScope scope, String language) {
        List<String> lines = new ArrayList<>();
        for (ChartEntry entry : entries) {
            if (entry.type() != EntryType.VITAL) {
                continue;
            }
            if (!scope.contains(entry.encounterId(), clinicalTime(entry))) {
                continue;
            }
            lines.add(ChartLabels.label("value", language) + ": " + entry.originalText() + " "
                    + entry.value() + " " + entry.unit() + " " + ChartLabels.label("time", language)
                    + " " + entry.measuredAt());
        }
        return List.copyOf(lines);
    }

    private static List<String> noteLines(List<ChartNote> notes, ExtractScope scope, String language,
                                          List<ExcludedItem> excluded) {
        List<String> lines = new ArrayList<>();
        for (ChartNote note : notes) {
            requireTime(note.effectiveAt(), "note effective time");
            if (!scope.contains(note.encounterId(), note.effectiveAt())) {
                continue;
            }
            if (note.state().signatureBearing()) {
                lines.add(note.title() + ": " + note.content() + " - "
                        + ChartLabels.label("signedBy", language) + ": " + note.signer());
            } else if (note.state() == NoteState.ENTERED_IN_ERROR) {
                excluded.add(new ExcludedItem(ChartSection.NOTES, note.id(), ExclusionReason.ENTERED_IN_ERROR));
            } else {
                excluded.add(new ExcludedItem(ChartSection.NOTES, note.id(), ExclusionReason.UNSIGNED_DRAFT));
            }
        }
        return List.copyOf(lines);
    }

    private static List<String> resultLines(List<ChartResult> results, ExtractScope scope, Instant now,
                                            String language, List<ExcludedItem> excluded) {
        List<String> lines = new ArrayList<>();
        for (ChartResult result : results) {
            requireTime(result.effectiveAt(), "result effective time");
            if (!scope.contains(result.encounterId(), result.effectiveAt())) {
                continue;
            }
            if (result.releaseState().visibleAt(result.effectiveAt(), now)) {
                lines.add(result.title() + ": " + result.content() + " - "
                        + ChartLabels.label("released", language) + ": " + result.effectiveAt());
            } else {
                excluded.add(new ExcludedItem(ChartSection.RESULTS, result.id(),
                        ExclusionReason.WITHHELD_RESULT));
            }
        }
        return List.copyOf(lines);
    }

    private static String typeLine(ChartEntry entry, String language) {
        return entry.originalText() + " (" + ChartLabels.presence(entry.presence(), language) + ")";
    }

    private static void requireTime(Instant time, String field) {
        if (time == null) {
            throw new ApiException(422, "INVALID_REQUEST", field + " is required for a chart extract");
        }
    }

    private static boolean isActive(ChartEntry entry, Instant now) {
        if (entry.presence() != PresenceState.PRESENT) {
            return false;
        }
        Instant end = entry.effectiveTo();
        return end == null || end.isAfter(now);
    }

    private static Instant clinicalTime(ChartEntry entry) {
        Instant time = entry.measuredAt();
        if (time == null) {
            time = entry.effectiveFrom();
        }
        if (time == null) {
            time = entry.recordedAt();
        }
        return time == null ? Instant.EPOCH : time;
    }

    private static String sourceId(String patientId, ExtractScope scope) {
        String selector = scope.isVisit()
                ? "visit-" + scope.visitId()
                : "range-" + scope.from() + "-" + scope.to();
        // A bounded digest keeps the tenant-local key stable across section choices
        // while staying inside the shared opaque-key alphabet.
        return "chart-" + NoteChecksum.sha256(patientId + "|" + selector).substring(0, 16);
    }

    private static String canonical(String organizationId, ExtractScope scope, SectionSelection selection,
                                    List<String> contentLines, List<ExcludedItem> excluded) {
        StringBuilder text = new StringBuilder();
        text.append(organizationId).append('|').append(scope.wire()).append('|');
        for (ChartSection section : ChartSection.values()) {
            if (selection.includes(section)) {
                text.append(section.wire()).append(',');
            }
        }
        text.append('|');
        for (String line : contentLines) {
            text.append(line).append('\n');
        }
        text.append('|');
        for (ExcludedItem item : excluded) {
            text.append(item.section().wire()).append(':').append(item.itemId()).append(':')
                    .append(item.reason().wire()).append(';');
        }
        return text.toString();
    }
}
