package com.aurevia.catalog.dictionary;

/**
 * Presentation metadata for one mixed-script text run (UX-01).
 *
 * <p>The {@code text} is the stored text unchanged: isolation is expressed as
 * {@code lang} and {@code dir} metadata for the client to apply, never as bidi
 * control characters or markup inserted into stored content. This keeps the
 * clipboard copy identical to the stored value.</p>
 *
 * @return this record
 */
public record MixedScriptRun(String text, String languageTag, RunDirection direction) {

    /**
     * Validate the annotation.
     *
     * @throws IllegalArgumentException when any component is missing
     */
    public MixedScriptRun {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (languageTag == null || languageTag.isBlank()) {
            throw new IllegalArgumentException("language tag is required");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is required");
        }
    }
}
