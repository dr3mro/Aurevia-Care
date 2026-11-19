package com.aurevia.smartclinic.documents;

import java.util.List;

/**
 * Value types shared by the document controller, service, repository and renderer (CLN-07).
 *
 * <p>They are grouped in one file to keep the story's change set small. Each
 * record has a single responsibility and is imported by its short name at every
 * use site.</p>
 */
public final class DocumentModel {

    private DocumentModel() {
    }

    /**
     * An immutable original linked to a chart, order or result (CLN-07).
     *
     * @param id              opaque association identifier
     * @param version         current revision, advanced only by a void
     * @param subjectType     chart, order or result
     * @param subjectId       opaque target identifier
     * @param fileName        original file name
     * @param mediaType       PDF, JPEG or PNG media type
     * @param byteSize        retained original length in bytes
     * @param sha256          hex digest of the retained original
     * @param rotationDegrees presentation-only quarter turn
     * @param status          active or voided
     * @param voidReason      recorded reason when voided
     * @param createdBy       authenticated author
     * @param createdAt       creation instant as text
     * @param voidedAt        void instant as text, or {@code null}
     */
    public record DocumentAssoc(String id, int version, String subjectType, String subjectId,
                                String fileName, String mediaType, long byteSize, String sha256,
                                int rotationDegrees, String status, String voidReason, String createdBy,
                                String createdAt, String voidedAt) {
    }

    /**
     * One append-only association history event (CLN-07).
     *
     * @param id            opaque event identifier
     * @param associationId association this event describes
     * @param action        link or void
     * @param reason        recorded reason, or {@code null} for a link
     * @param actor         authenticated actor derived by the database
     * @param occurredAt    occurrence instant as text
     */
    public record AssociationEvent(String id, String associationId, String action, String reason,
                                   String actor, String occurredAt) {
    }

    /**
     * An association together with its append-only history (CLN-07).
     *
     * @param association current association state
     * @param history     immutable link and void events in occurrence order
     */
    public record DocumentAssocView(DocumentAssoc association, List<AssociationEvent> history) {

        /**
         * Defensively copy the history on construction.
         *
         * @param association current association state
         * @param history     events to copy
         */
        public DocumentAssocView {
            history = List.copyOf(history);
        }

        @Override
        public List<AssociationEvent> history() {
            return List.copyOf(history);
        }
    }

    /**
     * Metadata for one immutable, versioned PDF render (CLN-07).
     *
     * @param id         opaque render identifier
     * @param version    render version within the subject and locale
     * @param subjectType chart, order or result
     * @param subjectId  opaque target identifier
     * @param locale     en or ar
     * @param direction  LTR or RTL
     * @param pageCount  number of pages in the immutable artifact
     * @param byteSize   retained PDF length in bytes
     * @param sha256     hex digest of the retained PDF
     * @param provenance source system and source identifier
     * @param renderedAt render instant as text
     * @param createdBy  authenticated author
     */
    public record RenderedDocument(String id, int version, String subjectType, String subjectId,
                                   String locale, String direction, int pageCount, long byteSize,
                                   String sha256, String provenance, String renderedAt, String createdBy) {
    }

    /**
     * Complete, deterministic input to the PDF renderer (CLN-07).
     *
     * <p>No clock or random source reaches the byte output: the same values always
     * render the same bytes. A new version or a different rotation produces a
     * distinct artifact while the source original stays untouched.</p>
     *
     * @param organizationId active tenant identifier
     * @param subjectType    chart, order or result
     * @param subjectId      opaque target identifier
     * @param locale         en or ar
     * @param version        render version
     * @param rotationDegrees presentation-only page rotation
     * @param title          document title
     * @param lines          content lines in logical order
     * @param sourceSystem   source system provenance
     * @param sourceId       source identifier provenance
     * @param effectiveAt    clinical effective instant as text
     */
    public record PdfSpec(String organizationId, String subjectType, String subjectId, String locale,
                          int version, int rotationDegrees, String title, List<String> lines,
                          String sourceSystem, String sourceId, String effectiveAt) {

        /**
         * Defensively copy the content lines on construction.
         *
         * @param organizationId active tenant identifier
         * @param subjectType    chart, order or result
         * @param subjectId      opaque target identifier
         * @param locale         en or ar
         * @param version        render version
         * @param rotationDegrees presentation-only page rotation
         * @param title          document title
         * @param lines          content lines in logical order
         * @param sourceSystem   source system provenance
         * @param sourceId       source identifier provenance
         * @param effectiveAt    clinical effective instant as text
         */
        public PdfSpec {
            lines = List.copyOf(lines);
        }

        @Override
        public List<String> lines() {
            return List.copyOf(lines);
        }
    }

    /**
     * Command to attach an original PDF or image (CLN-07).
     *
     * @param subjectType     chart, order or result
     * @param subjectId       opaque target identifier
     * @param fileName        original file name
     * @param mediaType       PDF, JPEG or PNG media type
     * @param contentBase64   base64-encoded original bytes
     * @param rotationDegrees presentation-only quarter turn
     */
    public record LinkRequest(String subjectType, String subjectId, String fileName, String mediaType,
                              String contentBase64, Integer rotationDegrees) {
    }

    /**
     * Command to render a localized, versioned PDF (CLN-07).
     *
     * @param subjectType        chart, order or result
     * @param subjectId          opaque target identifier
     * @param locale             en or ar
     * @param title              document title
     * @param lines              content lines in logical order
     * @param sourceSystem       source system provenance
     * @param sourceId           source identifier provenance
     * @param effectiveAt        clinical effective instant as text
     * @param rotationDegrees    presentation-only quarter turn
     * @param sourceAssociationId optional original this render was derived from
     */
    public record RenderRequest(String subjectType, String subjectId, String locale, String title,
                                List<String> lines, String sourceSystem, String sourceId,
                                String effectiveAt, Integer rotationDegrees, String sourceAssociationId) {

        /**
         * Defensively copy the content lines on construction.
         *
         * @param subjectType        chart, order or result
         * @param subjectId          opaque target identifier
         * @param locale             en or ar
         * @param title              document title
         * @param lines              content lines in logical order
         * @param sourceSystem       source system provenance
         * @param sourceId           source identifier provenance
         * @param effectiveAt        clinical effective instant as text
         * @param rotationDegrees    presentation-only quarter turn
         * @param sourceAssociationId optional original this render was derived from
         */
        public RenderRequest {
            lines = List.copyOf(lines);
        }

        @Override
        public List<String> lines() {
            return List.copyOf(lines);
        }
    }
}
