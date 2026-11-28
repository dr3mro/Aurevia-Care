package com.aurevia.printing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.documents.DocumentModel.PdfSpec;
import com.aurevia.documents.PdfRenderer;
import com.aurevia.printing.HandoutContract.Handout;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.printing.HandoutContract.RenderedHandout;

/**
 * Build a deterministic A4 handout PDF from one validated source revision.
 *
 * <p>The renderer delegates byte production to the shared document renderer so
 * font embedding, page numbering, A4 media box, direction marker and provenance
 * are defined in one place. This class only assembles the patient-facing lines
 * and the immutable metadata; no clock or random source is used for the bytes.</p>
 */
public final class HandoutRenderer {

    private final PdfRenderer pdfRenderer;

    /**
     * Create a renderer over the shared document PDF renderer.
     *
     * @param pdfRenderer deterministic PDF byte renderer
     */
    public HandoutRenderer(PdfRenderer pdfRenderer) {
        this.pdfRenderer = pdfRenderer;
    }

    /**
     * Render one immutable handout at a resolved version.
     *
     * @param source      validated source revision
     * @param language    {@code en} or {@code ar}
     * @param version     immutable version bound to this source
     * @param producedBy  authenticated requesting subject
     * @param producedAt  server render instant
     * @return rendered bytes and metadata
     */
    public RenderedHandout render(HandoutSource source, String language, int version,
                                  String producedBy, Instant producedAt) {
        byte[] bytes = pdfRenderer.render(spec(source, language, version));
        String checksum = NoteChecksum.sha256(HexFormat.of().formatHex(bytes));
        String handoutId = "handout/" + source.organizationId() + "/" + source.kind().wire()
                + "/" + source.sourceId() + "/v" + version + "/" + language;
        String provenance = source.sourceSystem() + "/" + source.sourceId()
                + " rev " + version + " " + source.contentChecksum().substring(0, 12);
        Handout handout = new Handout(source.organizationId(), handoutId, source.kind(),
                source.sourceId(), source.revisionVersion(), source.contentChecksum(), version,
                language, PdfRenderer.direction(language), 1, bytes.length, checksum, provenance,
                producedAt, producedBy);
        return new RenderedHandout(handout, bytes);
    }

    private static PdfSpec spec(HandoutSource source, String language, int version) {
        return new PdfSpec(source.organizationId(), source.kind().wire(), source.sourceId(),
                language, version, 0, source.kind().title(language), lines(source, language, version),
                source.sourceSystem(), source.sourceId(), source.issueTime().toString(), null);
    }

    private static List<String> lines(HandoutSource source, String language, int version) {
        List<String> lines = new ArrayList<>();
        lines.add(HandoutLabels.label("patient", language) + ": " + source.patientName());
        lines.add(HandoutLabels.label("patientId", language) + ": " + source.patientId());
        lines.add(HandoutLabels.label("clinician", language) + ": " + source.clinician());
        lines.add(HandoutLabels.label("facility", language) + ": " + source.facility());
        lines.add(HandoutLabels.label("issued", language) + ": " + source.issueTime());
        lines.addAll(source.contentLines());
        lines.add(HandoutLabels.label("provenance", language) + ": " + source.sourceSystem()
                + "/" + source.sourceId() + " rev " + version + " "
                + source.contentChecksum().substring(0, 12));
        return List.copyOf(lines);
    }
}
