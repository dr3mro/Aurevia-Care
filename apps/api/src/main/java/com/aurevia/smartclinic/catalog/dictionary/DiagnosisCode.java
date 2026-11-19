package com.aurevia.smartclinic.catalog.dictionary;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One diagnosis or procedure catalogue entry (CLN-03).
 *
 * <p>A mapping adds a market code and display text as new immutable values; the
 * original code, original text and original language tag are retained unchanged.
 * The code and its display text remain separate fields for bidi-safe display.</p>
 *
 * @return this record
 */
public record DiagnosisCode(String code, LocalizedText originalText, String mappedCode,
                            LocalizedText mappedText) {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}");

    /**
     * Validate the code and the mapping pair.
     *
     * @throws IllegalArgumentException when the code is malformed, text is missing,
     *                                  or only one mapping component is supplied
     */
    public DiagnosisCode {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid diagnosis or procedure code");
        }
        if (originalText == null) {
            throw new IllegalArgumentException("original text is required");
        }
        if ((mappedCode == null) != (mappedText == null)) {
            throw new IllegalArgumentException("mapped code and text must be supplied together");
        }
    }

    /**
     * Return a mapped copy while preserving the original code and text.
     *
     * @param newCode market code
     * @param newText market display text with its language tag
     * @return a new mapped entry; this entry is unchanged
     */
    public DiagnosisCode withMapping(String newCode, LocalizedText newText) {
        return new DiagnosisCode(code, originalText, newCode, newText);
    }

    /**
     * Whether this entry carries a market mapping.
     *
     * @return {@code true} when mapped
     */
    public boolean isMapped() {
        return mappedCode != null;
    }

    /**
     * Case-insensitive search over code, original text and mapped fields.
     *
     * <p>Search normalizes the query for matching only; stored spellings are
     * never changed.</p>
     *
     * @param query search fragment
     * @return {@code true} when any field contains the fragment
     */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return code.toLowerCase(Locale.ROOT).contains(needle)
                || originalText.text().toLowerCase(Locale.ROOT).contains(needle)
                || (mappedCode != null && mappedCode.toLowerCase(Locale.ROOT).contains(needle))
                || (mappedText != null && mappedText.text().toLowerCase(Locale.ROOT).contains(needle));
    }
}
