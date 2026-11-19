package com.aurevia.smartclinic.catalog.dictionary;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One field definition in a versioned clinical dictionary (CLN-03, CLN-04).
 *
 * <p>A field carries a localized label, an explicit requiredness code and an
 * optional bounded set of allowed values. The value list is defensively copied
 * so a caller cannot mutate a saved dictionary version.</p>
 *
 * @param code          tenant-local field code
 * @param label         localized label shown to clinical staff
 * @param requiredness  explicit requiredness decision
 * @param allowedValues bounded allowed values, or {@code null} for free text
 */
public record DictionaryField(String code, LocalizedText label, Requiredness requiredness,
                              List<String> allowedValues) {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}");

    /**
     * Validate and defensively copy the field definition.
     *
     * @throws IllegalArgumentException when a required component is missing or malformed
     */
    public DictionaryField {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid field code");
        }
        if (label == null) {
            throw new IllegalArgumentException("label is required");
        }
        if (requiredness == null) {
            throw new IllegalArgumentException("requiredness is required");
        }
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    /**
     * Return an immutable copy of the allowed values.
     *
     * @return the allowed values
     */
    @Override
    public List<String> allowedValues() {
        return List.copyOf(allowedValues);
    }
}
