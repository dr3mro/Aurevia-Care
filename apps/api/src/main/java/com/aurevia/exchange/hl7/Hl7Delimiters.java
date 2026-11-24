package com.aurevia.exchange.hl7;

/**
 * Delimiter characters declared by the MSH segment of an HL7 v2 message.
 *
 * <p>MSH-1 supplies the field separator and MSH-2 supplies the component,
 * repetition, escape and subcomponent characters in that order. A partner may
 * choose non-standard characters, so the parser reads them from the first
 * segment instead of assuming the standard set (XCH-01, XCH-04).</p>
 *
 * @param field       field separator (MSH-1)
 * @param component   component separator (first character of MSH-2)
 * @param repetition  repetition separator (second character of MSH-2)
 * @param escape      escape character (third character of MSH-2)
 * @param subcomponent subcomponent separator (fourth character of MSH-2)
 */
public record Hl7Delimiters(char field, char component, char repetition, char escape,
                            char subcomponent) {

    /**
     * Return the standard HL7 v2 delimiter set {@code |^~\&}.
     *
     * @return the standard delimiters
     */
    public static Hl7Delimiters standard() {
        return new Hl7Delimiters('|', '^', '~', '\\', '&');
    }

    /**
     * Read the delimiters from an MSH segment line.
     *
     * @param mshLine first segment line of the message
     * @return delimiters declared by that segment
     * @throws Hl7ParseException when the line is not a long enough MSH segment
     */
    public static Hl7Delimiters fromMsh(String mshLine) {
        if (mshLine == null || mshLine.length() < 8 || !mshLine.startsWith("MSH")) {
            throw new Hl7ParseException("INVALID_MSH", "MSH header is missing or too short");
        }
        return new Hl7Delimiters(mshLine.charAt(3), mshLine.charAt(4), mshLine.charAt(5),
                mshLine.charAt(6), mshLine.charAt(7));
    }
}
