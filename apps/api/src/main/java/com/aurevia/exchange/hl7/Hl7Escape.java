package com.aurevia.exchange.hl7;

/**
 * Codec for HL7 v2 escape sequences.
 *
 * <p>HL7 v2 reserves the delimiter characters inside a field, so a literal
 * separator is written as {@code \F\}, {@code \S\}, {@code \R\}, {@code \E\} or
 * {@code \T\}. A hexadecimal form {@code \Xhhhh\} carries characters that
 * cannot appear directly, and {@code \H\}/\N\ are highlighting markers that are
 * discarded on parse. Carriage return and line feed are always encoded as hex
 * so a field value can never inject a new segment.</p>
 */
public final class Hl7Escape {

    private Hl7Escape() {
    }

    /**
     * Escape a value for placement in one HL7 field.
     *
     * @param value      raw value, may be null
     * @param delimiters delimiters declared by the message
     * @return escaped value, or an empty string when the value is null
     */
    public static String encode(String value, Hl7Delimiters delimiters) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            out.append(encodeCharacter(value.charAt(index), delimiters));
        }
        return out.toString();
    }

    /**
     * Decode one HL7 field value.
     *
     * <p>An unknown or truncated escape sequence is preserved literally rather
     * than dropped, so malformed partner data remains visible to review.</p>
     *
     * @param value      escaped value, may be null
     * @param delimiters delimiters declared by the message
     * @return decoded value, or an empty string when the value is null or empty
     */
    public static String decode(String value, Hl7Delimiters delimiters) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current != delimiters.escape() || index + 1 >= value.length()) {
                out.append(current);
                index++;
                continue;
            }
            char code = value.charAt(index + 1);
            if (code == 'X') {
                int end = value.indexOf(delimiters.escape(), index + 2);
                if (end >= 0) {
                    String decoded = decodeHex(value.substring(index + 2, end));
                    if (decoded != null) {
                        out.append(decoded);
                        index = end + 1;
                        continue;
                    }
                }
                out.append(current);
                index++;
                continue;
            }
            if (index + 2 >= value.length() || value.charAt(index + 2) != delimiters.escape()) {
                out.append(current);
                index++;
                continue;
            }
            String decoded = decodeCode(code, delimiters);
            if (decoded == null) {
                out.append(current);
                index++;
                continue;
            }
            out.append(decoded);
            index += 3;
        }
        return out.toString();
    }

    /**
     * Map one single-character escape code to its literal value.
     *
     * @param code       escape code following the escape character
     * @param delimiters delimiters declared by the message
     * @return decoded literal, or null when the code is unknown
     */
    private static String decodeCode(char code, Hl7Delimiters delimiters) {
        return switch (code) {
            case 'F' -> String.valueOf(delimiters.field());
            case 'S' -> String.valueOf(delimiters.component());
            case 'R' -> String.valueOf(delimiters.repetition());
            case 'T' -> String.valueOf(delimiters.subcomponent());
            case 'E' -> String.valueOf(delimiters.escape());
            case 'H', 'N' -> "";
            default -> null;
        };
    }

    /**
     * Decode an even-length run of hexadecimal digits.
     *
     * @param hex digits between {@code \X} and the closing escape character
     * @return decoded characters, or null when the run is not valid hex
     */
    private static String decodeHex(String hex) {
        if (hex.isEmpty() || hex.length() % 2 != 0 || hex.length() > 4) {
            return null;
        }
        StringBuilder out = new StringBuilder(hex.length() / 2);
        for (int index = 0; index < hex.length(); index += 2) {
            int high = Character.digit(hex.charAt(index), 16);
            int low = Character.digit(hex.charAt(index + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            out.append((char) ((high << 4) | low));
        }
        return out.toString();
    }

    /**
     * Escape one character for placement in a field.
     *
     * @param character raw character
     * @param delimiters delimiters declared by the message
     * @return escaped representation
     */
    private static String encodeCharacter(char character, Hl7Delimiters delimiters) {
        if (character == delimiters.field()) {
            return escapeCode(delimiters, 'F');
        }
        if (character == delimiters.component()) {
            return escapeCode(delimiters, 'S');
        }
        if (character == delimiters.repetition()) {
            return escapeCode(delimiters, 'R');
        }
        if (character == delimiters.subcomponent()) {
            return escapeCode(delimiters, 'T');
        }
        if (character == delimiters.escape()) {
            return escapeCode(delimiters, 'E');
        }
        if (character == '\r') {
            return String.valueOf(delimiters.escape()) + "X0d" + delimiters.escape();
        }
        if (character == '\n') {
            return String.valueOf(delimiters.escape()) + "X0a" + delimiters.escape();
        }
        return String.valueOf(character);
    }

    /**
     * Build a single-character escape sequence using the message escape char.
     *
     * @param delimiters delimiters declared by the message
     * @param code       escape code letter
     * @return sequence such as {@code \F\}
     */
    private static String escapeCode(Hl7Delimiters delimiters, char code) {
        return String.valueOf(delimiters.escape()) + code + delimiters.escape();
    }
}
