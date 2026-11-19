package com.aurevia.smartclinic.g1.s_g1_08;

/**
 * Document direction derived from an interface locale (UX-01).
 *
 * <p>Direction is a presentation property, never a fact persisted on a clinical
 * record. The server derives it from the interface locale so the client can mirror
 * the document shell consistently.</p>
 */
public enum Direction {

    /** Left-to-right document flow. */
    LTR("ltr"),
    /** Right-to-left document flow. */
    RTL("rtl");

    private final String attribute;

    Direction(String attribute) {
        this.attribute = attribute;
    }

    /**
     * Return the lower-case markup attribute value.
     *
     * @return {@code ltr} or {@code rtl}
     */
    public String attribute() {
        return attribute;
    }
}
