package com.aurevia.smartclinic.g4.s_g4_01;

import java.time.Instant;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.g1.s_g1_05.NoteChecksum;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.InterpretationKind;

/**
 * One immutable signed interpretation or addendum (RAD-01, CLN-05, ARC-01).
 *
 * <p>A revision records technique, comparison, findings, impression and follow-up
 * together with the signer, attestation, signing time and template version. A
 * prior signed revision is preserved when an addendum links to it. The checksum
 * is derived from the clinical content so a rendered or transmitted copy can be
 * verified against the revision it claims to represent.</p>
 *
 * @param organizationId   owning tenant
 * @param orderId          tenant-local imaging order identifier
 * @param version          monotonic interpretation revision version
 * @param supersedesVersion prior interpretation version, or {@code null} for the initial report
 * @param kind             initial interpretation or linked addendum
 * @param patientId        patient identifier matched at signing
 * @param studyInstanceUid external study this interpretation belongs to
 * @param technique        imaging technique documented by the interpreter
 * @param comparison       comparison studies documented by the interpreter
 * @param findings         documented findings
 * @param impression       documented impression
 * @param followUp         documented follow-up
 * @param signer           authenticated interpreting subject
 * @param attestation      signer attestation text
 * @param signedAt         server time at which the interpretation was signed
 * @param templateVersion  version of the interpretation template used
 * @param reason           required reason for an addendum, or {@code null}
 */
public record Interpretation(String organizationId, String orderId, int version,
                             Integer supersedesVersion, InterpretationKind kind, String patientId,
                             String studyInstanceUid, String technique, String comparison,
                             String findings, String impression, String followUp, String signer,
                             String attestation, Instant signedAt, String templateVersion,
                             String reason) {

    /**
     * Validate the immutable ownership, version and signature invariants.
     *
     * @throws ApiException when tenant, order identity, version, kind or signer is invalid
     */
    public Interpretation {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ApiException(422, "INVALID_INTERPRETATION", "active organization is required");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new ApiException(422, "INVALID_INTERPRETATION", "order identifier is required");
        }
        if (version < 1 || (supersedesVersion != null && supersedesVersion >= version)) {
            throw new ApiException(422, "INVALID_INTERPRETATION", "invalid interpretation version or link");
        }
        if (kind == null) {
            throw new ApiException(422, "INVALID_INTERPRETATION", "interpretation kind is required");
        }
        if (signer == null || signer.isBlank()) {
            throw new ApiException(422, "INVALID_INTERPRETATION", "signer is required");
        }
    }

    /**
     * Whether this revision is a linked addendum rather than the initial report.
     *
     * @return {@code true} for an addendum
     */
    public boolean isAddendum() {
        return kind == InterpretationKind.ADDENDUM;
    }

    /**
     * Compute a deterministic SHA-256 content digest for this signed revision.
     *
     * <p>Length-prefixed fields prevent delimiter collisions between adjacent
     * values. The digest covers ownership, version, the clinical narrative and
     * the signature metadata (signer and attestation), but not the signing
     * instant, so re-rendering the same content yields the same fixed-length
     * digest. A cryptographic digest, rather than an encoding of the content,
     * keeps the checksum bounded and suitable for verifying a rendered copy.</p>
     *
     * @return the 64-character lower-case hexadecimal SHA-256 content digest
     */
    public String checksum() {
        StringBuilder builder = new StringBuilder();
        append(builder, organizationId);
        append(builder, orderId);
        append(builder, String.valueOf(version));
        append(builder, String.valueOf(supersedesVersion));
        append(builder, kind.wire());
        append(builder, patientId);
        append(builder, studyInstanceUid);
        append(builder, technique);
        append(builder, comparison);
        append(builder, findings);
        append(builder, impression);
        append(builder, followUp);
        append(builder, signer);
        append(builder, attestation);
        append(builder, templateVersion);
        append(builder, reason);
        // Intentional reuse of the g1.s_g1_05 SHA-256 utility: keep one digest rule
        // rather than adding a fourth local copy; no clinical rule crosses packages.
        return NoteChecksum.sha256(builder.toString());
    }

    private static void append(StringBuilder builder, String value) {
        String text = value == null ? "" : value;
        builder.append(text.length()).append(':').append(text);
    }
}
