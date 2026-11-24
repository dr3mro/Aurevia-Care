package com.aurevia.catalog.dictionary;

/**
 * Per-run direction metadata for a text value (UX-01).
 *
 * <p>Run direction is decided by the value, not by the interface locale. Free text
 * uses {@code auto} so the client can isolate foreign-script runs; identifiers and
 * codes stay left-to-right in both interface locales.</p>
 */
public enum RunDirection {

    /** Let the content decide the run direction, with isolation metadata. */
    AUTO("auto"),
    /** Force a left-to-right run for codes and identifiers. */
    LTR("ltr");

    private final String attribute;

    /**
     * @param attribute
     */
    RunDirection(String attribute) {
        this.attribute = attribute;
    }

    /**
     * Return the lower-case markup attribute value.
     *
     * @return {@code auto} or {@code ltr}
     */
    public String attribute() {
        return attribute;
    }
}
