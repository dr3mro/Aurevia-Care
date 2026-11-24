package com.aurevia.documents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * @return this record
 */
public record DocumentAssoc(String id, int version, String subjectType, String subjectId,
                                String fileName, String mediaType, long byteSize, String sha256,
                                int rotationDegrees, String status, String voidReason, String createdBy,
                                String createdAt, String voidedAt) {
    }

    /**
     * One append-only association history event (CLN-07).
     *
 * @return this record
 */
public record AssociationEvent(String id, String associationId, String action, String reason,
                                   String actor, String occurredAt) {
    }

    /**
     * Retained original bytes and whether the database re-verified their digest (CLN-07).
     *
     * <p>{@code intact} is false when the bytes held in storage no longer match
     * the SHA-256 captured on upload; those bytes must never be served as the
     * verified original. The byte array is defensively copied so a caller cannot
     * mutate the value after the database returned it.</p>
     *
  * @return this record
  */
public record VerifiedContent(byte[] content, boolean intact) {

        /**
         * Freeze the retained bytes on construction.
         *
         */
        public VerifiedContent {
            content = Arrays.copyOf(content, content.length);
        }

        @Override
        public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }

    /**
     * An association together with its append-only history (CLN-07).
     *
 * @return this record
 */
public record DocumentAssocView(DocumentAssoc association, List<AssociationEvent> history) {

        /**
         * Defensively copy the history on construction.
         *
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
 * @return this record
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
     * distinct artifact while the source original stays untouched. When the
     * render derives from a linked original, {@code sourceAssociationId} is the
     * server-validated association and is stamped into the immutable PDF's
     * provenance; caller-supplied source text alone is never the only lineage.</p>
     *
 * @return this record
 */
public record PdfSpec(String organizationId, String subjectType, String subjectId, String locale,
                          int version, int rotationDegrees, String title, List<String> lines,
                          String sourceSystem, String sourceId, String effectiveAt,
                          String sourceAssociationId) {

        /**
         * Defensively copy the content lines on construction.
         *
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
 * @return this record
 */
public record LinkRequest(String subjectType, String subjectId, String fileName, String mediaType,
                              String contentBase64, Integer rotationDegrees) {
    }

    /**
     * Command to render a localized, versioned PDF (CLN-07).
     *
 * @return this record
 */
public record RenderRequest(String subjectType, String subjectId, String locale, String title,
                                List<String> lines, String sourceSystem, String sourceId,
                                String effectiveAt, Integer rotationDegrees, String sourceAssociationId) {

        /**
         * Keep an immutable view without rejecting boundary values that the
         * dedicated validator must report as a machine-readable 422. Jackson
         * may supply a null list or null elements; {@code List.copyOf} would
         * throw an unmapped {@code NullPointerException} before validation.
         */
        public RenderRequest {
            lines = copyLines(lines);
        }

        @Override
        public List<String> lines() {
            return copyLines(lines);
        }

        private static List<String> copyLines(List<String> values) {
            return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
