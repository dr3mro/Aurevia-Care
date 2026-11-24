package com.aurevia.catalog.dictionary;

import java.util.List;

import com.aurevia.api.contract.CatalogCode;

/**
 * One field definition in a versioned clinical dictionary (CLN-03, CLN-04).
 *
 * <p>A field carries a localized label, an explicit requiredness code and an
 * optional bounded set of allowed values. The value list is defensively copied
 * so a caller cannot mutate a saved dictionary version.</p>
 *
 * @return this record
 */
public record DictionaryField(String code, LocalizedText label, Requiredness requiredness,
                              List<String> allowedValues) {

    /**
     * Validate and defensively copy the field definition.
     *
     * @throws IllegalArgumentException when a required component is missing or malformed
     */
    public DictionaryField {
        CatalogCode.code(code);
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
