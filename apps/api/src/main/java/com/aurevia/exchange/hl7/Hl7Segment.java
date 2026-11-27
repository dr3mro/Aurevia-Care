package com.aurevia.exchange.hl7;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One parsed HL7 v2 segment with its fields and repetitions.
 *
 * <p>Field 1 is the three-character segment identifier. Each subsequent field
 * holds one or more repetitions, already decoded by {@link com.aurevia.exchange.hl7.Hl7Escape}; splitting
 * on the repetition separator happens before decoding so an escaped {@code \R\}
 * remains literal data rather than becoming a spurious repetition.</p>
 *
 * @param type        three-character segment identifier
 * @param repetitions field repetitions indexed from field 1
 */
public record Hl7Segment(String type, List<List<String>> repetitions) {

    /**
     * Freeze the nested repetition lists.
     */
    public Hl7Segment {
        repetitions = List.copyOf(repetitions.stream().map(List::copyOf).toList());
    }

    /**
     * Return an immutable view of the field repetitions.
     *
     * @return field repetitions indexed from field 1
     */
    @Override
    public List<List<String>> repetitions() {
        return List.copyOf(repetitions.stream().map(List::copyOf).toList());
    }

    /**
     * Return the first repetition of a field.
     *
     * @param oneBasedField one-based field index including the segment identifier
     * @return first repetition, or an empty string when the field is absent
     */
    public String field(int oneBasedField) {
        if (oneBasedField < 1 || oneBasedField > repetitions.size()) {
            return "";
        }
        List<String> values = repetitions.get(oneBasedField - 1);
        return values.isEmpty() ? "" : values.get(0);
    }

    /**
     * Return every repetition of a field in wire order.
     *
     * @param oneBasedField one-based field index including the segment identifier
     * @return immutable repetition values, empty when the field is absent
     */
    public List<String> repetitionValues(int oneBasedField) {
        if (oneBasedField < 1 || oneBasedField > repetitions.size()) {
            return List.of();
        }
        return List.copyOf(repetitions.get(oneBasedField - 1));
    }

    /**
     * Return one component of a field using the message component separator.
     *
     * @param oneBasedField     one-based field index
     * @param oneBasedComponent one-based component index
     * @param componentSeparator component separator character
     * @return component value, or an empty string when either index is absent
     */
    public String component(int oneBasedField, int oneBasedComponent, char componentSeparator) {
        String value = field(oneBasedField);
        if (value.isEmpty()) {
            return "";
        }
        String[] parts = value.split(Pattern.quote(String.valueOf(componentSeparator)), -1);
        if (oneBasedComponent < 1 || oneBasedComponent > parts.length) {
            return "";
        }
        return parts[oneBasedComponent - 1];
    }
}
