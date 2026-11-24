package com.aurevia.orderentry.catalog;

/**
 * The clinical family an orderable catalog item belongs to (LAB-01, RAD-01).
 *
 * <p>The kind decides how the single {@code specimenOrModality} attribute of an
 * {@link com.aurevia.orderentry.catalog.OrderableItem} is interpreted:
 * a specimen type for a laboratory test, an approved imaging modality for a
 * study. It is recorded, never inferred from free text.</p>
 */
public enum OrderableKind {

    /** A laboratory test performed on a collected specimen. */
    LAB_TEST,
    /** An imaging study performed with an approved modality. */
    IMAGING_STUDY
}
