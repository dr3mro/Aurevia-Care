package com.aurevia.labflow.outage;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.aurevia.api.ApiException;
import com.aurevia.documents.UploadValidation;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.labflow.outage.OutageModel.EvidenceAttachment;
import com.aurevia.labflow.results.ObservationInput;

/**
 * Pure partner-outage fallback validation and matching rules (LAB-02, XCH-03, PRD-02).
 *
 * <p>Every client-supplied value is bounded before a transaction opens, so a
 * malformed command cannot consume a connection or reach a domain invariant.
 * The shared upload primitive validates the path-free file name and SHA-256
 * checksum; the outage-specific object key and media type stay local because no
 * upload-command primitive owns an arbitrary retained-report key. Identity
 * matching repeats the accession label check before anything is linked, so a
 * report presented with the wrong patient, accession or issuer is never attached
 * to an order. The class holds no state and every method is directly unit tested.</p>
 */
public final class OutageDecision {

    private static final int MAX_TEXT = 200;
    private static final Pattern OBJECT_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_.:-]{1,127}");
    private static final Pattern CONTENT_TYPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+/-]{1,63}");

    private OutageDecision() {
    }

    /**
     * Validate and trim the non-sensitive justification for a manual fallback.
     *
     * @param value caller-supplied reason, possibly {@code null}
     * @return the trimmed bounded reason
     * @throws ApiException when the reason is absent, blank or oversized
     */
    public static String reason(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "REASON_REQUIRED", "a reason is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new ApiException(422, "REASON_REQUIRED", "reason is too long");
        }
        return trimmed;
    }

    /**
     * Validate and trim the named owner of an unmatched entry.
     *
     * @param value caller-supplied owner, possibly {@code null}
     * @return the trimmed bounded owner
     * @throws ApiException when the owner is absent, blank or oversized
     */
    public static String owner(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "OWNER_REQUIRED", "an owner is required for an unmatched result");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new ApiException(422, "OWNER_REQUIRED", "owner is too long");
        }
        return trimmed;
    }

    /**
     * Validate every field of the retained original report.
     *
     * @param value caller-supplied evidence attachment, possibly {@code null}
     * @return the validated attachment
     * @throws ApiException when the attachment or any of its fields is invalid
     */
    public static EvidenceAttachment attachment(EvidenceAttachment value) {
        if (value == null) {
            throw new ApiException(422, "ATTACHMENT_REQUIRED", "the original report must be attached");
        }
        String key = bounded(value.objectKey(), OBJECT_KEY, "INVALID_OBJECT_KEY",
                "objectKey is malformed");
        String fileName = UploadValidation.fileName(value.fileName());
        String checksum = UploadValidation.checksum(value.checksum());
        String contentType = bounded(value.contentType(), CONTENT_TYPE, "INVALID_CONTENT_TYPE",
                "contentType is malformed");
        return new EvidenceAttachment(key, fileName, checksum, contentType);
    }

    /**
     * Require at least one keyed-in observation and summarise its test codes.
     *
     * @param values caller-supplied observations, possibly {@code null}
     * @return comma-separated codes in submission order
     * @throws ApiException when no observation was supplied
     */
    public static String observationSummary(List<ObservationInput> values) {
        if (values == null || values.isEmpty()) {
            throw new ApiException(422, "NO_OBSERVATIONS", "at least one observation is required");
        }
        return values.stream().map(ObservationInput::code).collect(Collectors.joining(","));
    }

    /**
     * Repeat the bedside label match before any report is linked to an order.
     *
     * @param accession   stored requisition found by the presented order reference
     * @param patientId   presented patient identity
     * @param accessionNo presented accession number
     * @param issuer      presented accession issuer
     * @return {@code true} only when all three identifiers agree
     */
    public static boolean matches(LabAccession accession, String patientId, String accessionNo,
                                  String issuer) {
        return accession.patientId().equals(patientId)
                && accession.accessionNo().equals(accessionNo)
                && accession.accessionIssuer().equals(issuer);
    }

    private static String bounded(String value, Pattern pattern, String code, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new ApiException(422, code, message);
        }
        return value;
    }
}
