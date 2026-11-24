package com.aurevia.lab.result;

import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;

import java.math.BigDecimal;

import com.aurevia.api.ApiException;

/**
 * One immutable laboratory observation with its provenance (LAB-02).
 *
 * <p>The four kinds share one representation so a caller cannot lose the kind at
 * a boundary. A measured numeric value is a {@code BigDecimal}, never a floating
 * point number, and a qualifier is retained beside it so a less-than result is
 * never mistaken for its bound. Unknown and absent observations carry no value;
 * a true zero is an ordinary measured value.</p>
 *
 * @return this record
 */
public record LabObservation(String code, ObservationKind kind, ObservationPresence presence,
                          BigDecimal value, Qualifier qualifier, String unit, String textValue,
                          String codedValue, String method, AbnormalFlag abnormalFlag,
                          String instrument, String verifier) {

    /**
     * Validate that the value fields agree with the declared kind and presence.
     *
     * @throws ApiException when a required field is missing or a value is carried by the wrong kind
     */
    public LabObservation {
        if (code == null || code.isBlank() || kind == null || presence == null
                || qualifier == null || abnormalFlag == null) {
            throw new ApiException(422, "INVALID_OBSERVATION", "observation identity and kind are required");
        }
        if (presence == ObservationPresence.PRESENT) {
            if (kind == ObservationKind.NUMERIC || kind == ObservationKind.QUALIFIED_NUMERIC) {
                if (value == null || unit == null || unit.isBlank()) {
                    throw new ApiException(422, "INVALID_OBSERVATION", "a numeric observation needs a value and unit");
                }
                if (kind == ObservationKind.NUMERIC && qualifier != Qualifier.NONE) {
                    throw new ApiException(422, "INVALID_QUALIFIER", "an exact numeric result cannot be qualified");
                }
                if (kind == ObservationKind.QUALIFIED_NUMERIC && qualifier == Qualifier.NONE) {
                    throw new ApiException(422, "INVALID_QUALIFIER", "a qualified numeric result needs an operator");
                }
            } else if (kind == ObservationKind.TEXT) {
                if (textValue == null || value != null) {
                    throw new ApiException(422, "INVALID_OBSERVATION", "a text observation needs text and no number");
                }
            } else if (codedValue == null || codedValue.isBlank() || value != null) {
                throw new ApiException(422, "INVALID_OBSERVATION", "a coded observation needs a code and no number");
            }
        } else if (value != null) {
            throw new ApiException(422, "INVALID_OBSERVATION", "an unknown or absent observation carries no value");
        }
    }

    /**
     * Return the number only when it is an exact, measured value (XCH-07).
     *
     * @return the value for a present exact numeric observation, otherwise {@code null}
     */
    public BigDecimal exactValueOrNull() {
        // Validation already forbids a qualified exact numeric observation.
        return presence == ObservationPresence.PRESENT && kind == ObservationKind.NUMERIC ? value : null;
    }

    /**
     * Render the observation without losing its qualifier, unit or presence state.
     *
     * @return a non-sensitive display form used by projections and event digests
     */
    public String display() {
        if (presence == ObservationPresence.UNKNOWN) {
            return "unknown";
        }
        if (presence == ObservationPresence.ABSENT) {
            return "absent";
        }
        return switch (kind) {
            case NUMERIC -> value.toPlainString() + " " + unit + " (" + abnormalFlag.name() + ")";
            case QUALIFIED_NUMERIC -> qualifier.symbol() + value.toPlainString() + " " + unit;
            case TEXT -> textValue.isEmpty() ? "(empty text)" : textValue;
            case CODED -> codedValue;
        };
    }

    /**
     * Return a new observation with verification evidence; the original is unchanged.
     *
     * @param verifier     authenticated verifier recorded on the new observation
     * @param abnormalFlag interpretation established by evaluation
     * @return a new immutable observation
     */
    public LabObservation verified(String verifier, AbnormalFlag abnormalFlag) {
        return new LabObservation(code, kind, presence, value, qualifier, unit, textValue, codedValue,
                method, abnormalFlag, instrument, verifier);
    }
}
