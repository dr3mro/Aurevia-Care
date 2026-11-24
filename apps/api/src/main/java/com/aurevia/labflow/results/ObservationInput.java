package com.aurevia.labflow.results;

/**
 * One bounded observation supplied at the result-entry boundary (LAB-02).
 *
 * <p>The four wire kinds stay in one command shape so a caller cannot silently
 * drop a qualifier, a unit or a presence state. Values arrive as text and are
 * parsed to {@link java.math.BigDecimal} by the service; the site's approved
 * reference and critical bounds travel with the observation so the flags can be
 * derived without a hard-coded threshold. Organization, actor and verifier are
 * never accepted from the client.</p>
 *
 * @param code          bounded test code
 * @param kind          numeric, qualified_numeric, text or coded
 * @param presence      present, unknown or absent
 * @param value         decimal text for numeric kinds, otherwise {@code null}
 * @param qualifier     none, lt, gt, lte or gte; {@code null} means none
 * @param unit          unit for numeric kinds
 * @param textValue     text result for the text kind
 * @param codedValue    coded result for the coded kind
 * @param method        optional bounded method description
 * @param instrument    optional bounded instrument or source
 * @param referenceLow  approved reference lower bound, or {@code null}
 * @param referenceHigh approved reference upper bound, or {@code null}
 * @param criticalLow   approved critical lower bound, or {@code null}
 * @param criticalHigh  approved critical upper bound, or {@code null}
 * @return this record
 */
public record ObservationInput(String code, String kind, String presence, String value,
                               String qualifier, String unit, String textValue, String codedValue,
                               String method, String instrument, String referenceLow,
                               String referenceHigh, String criticalLow, String criticalHigh) {
}
