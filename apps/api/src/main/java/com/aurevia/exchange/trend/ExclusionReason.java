package com.aurevia.exchange.trend;

/**
 * Machine-readable reason an observation is visible but not part of the numeric
 * trend (XCH-07). A closed enumeration keeps exclusions from being silent.
 */
public enum ExclusionReason {

    /** The observation belongs to another test identity. */
    DIFFERENT_TEST,
    /** The result was produced by a different comparability group or assay. */
    DIFFERENT_COMPARABILITY_GROUP,
    /** The revision is preliminary, corrected, or entered in error. */
    NON_FINAL_REVISION,
    /** A less-than or greater-than result; its literal number is not a point. */
    QUALIFIED_NUMERIC,
    /** A qualitative or coded result with no numeric value. */
    QUALITATIVE,
    /** The reported unit is not the canonical unit; no conversion is attempted. */
    INCOMPATIBLE_UNIT,
    /** No effective comparability rule is established for this test. */
    COMPARABILITY_NOT_ESTABLISHED,
    /** The established rule explicitly marks the test as not trendable. */
    NOT_TRENDABLE
}
