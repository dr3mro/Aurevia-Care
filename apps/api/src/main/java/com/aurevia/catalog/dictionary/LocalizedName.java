package com.aurevia.catalog.dictionary;

/**
 * A recorded name with an optional local-script form (UX-01, IAM-03).
 *
 * <p>Both spellings are stored as recorded; the interface never invents a
 * transliteration. Display selection is deterministic and lives here so every
 * screen applies the same precedence.</p>
 *
 * @return this record
 */
public record LocalizedName(String latin, String localScript) {

    /**
     * Validate that at least one recorded form exists.
     *
     * @throws IllegalArgumentException when both forms are missing
     */
    public LocalizedName {
        if (isBlank(latin) && isBlank(localScript)) {
            throw new IllegalArgumentException("at least one recorded name form is required");
        }
    }

    /**
     * Whether both recorded forms are present.
     *
     * @return {@code true} when both spellings are recorded
     */
    public boolean hasBoth() {
        return !isBlank(latin) && !isBlank(localScript);
    }

    /**
     * Select a single display form for the given interface locale.
     *
     * <p>An Arabic interface prefers the local script and falls back to the Latin
     * form; an English interface does the reverse.</p>
     *
     * @param ui the interface locale
     * @return the selected recorded spelling
     */
    public String display(UiLocale ui) {
        if (ui == UiLocale.AR && !isBlank(localScript)) {
            return localScript;
        }
        return !isBlank(latin) ? latin : localScript;
    }

    /**
     * Select both forms for an identity-sensitive context (UX-01 section 6).
     *
     * @param ui the interface locale
     * @return both spellings in locale order, or the single available spelling
     */
    public String displayBoth(UiLocale ui) {
        if (isBlank(latin) || isBlank(localScript) || latin.equals(localScript)) {
            return display(ui);
        }
        return ui == UiLocale.AR ? localScript + " / " + latin : latin + " / " + localScript;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
