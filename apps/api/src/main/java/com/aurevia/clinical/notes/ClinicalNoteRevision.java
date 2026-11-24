package com.aurevia.clinical.notes;

/**
 * One immutable revision of a clinical note in the active organization (CLN-05).
 *
 * <p>The clinical author, the data-entry actor and the signer are recorded
 * separately: authorship belongs to the clinician, entry to the user who typed
 * the draft, and the signature to the clinician who attested to a specific
 * version. Signing metadata, the content checksum and the template version are
 * carried by the revision itself so history stays self-describing.</p>
 *
 * @param id                     opaque note identifier shared by every revision
 * @param version                monotonic revision version within the note
 * @param state                  lifecycle state of this revision
 * @param patientId              patient whose chart owns the note
 * @param encounterId            optional encounter context, when one exists
 * @param title                  revision title
 * @param content                clinical content
 * @param language               content language
 * @param templateCode           template identifier that shaped the content
 * @param templateVersion        version of the template used
 * @param clinicalAuthorSubject  clinically responsible author
 * @param dataEntrySubject       user that entered the data
 * @param effectiveAt            clinical effective instant
 * @param signedAt               signature instant, or {@code null}
 * @param signerSubject          attesting signer, or {@code null}
 * @param attestation            signer attestation text, or {@code null}
 * @param contentChecksum        server-computed SHA-256 of the content
 * @param supersedesVersion      prior version this revision supersedes, or {@code null}
 * @param enteredErrorReason     entered-in-error reason, or {@code null}
 * @param enteredErrorActor      user that recorded entered-in-error, or {@code null}
 * @param affectedRecipients     recipient identifiers as a JSON array
 * @param createdAt              ingestion instant
 * @return this record
 */
public record ClinicalNoteRevision(String id, int version, NoteState state, String patientId, String encounterId,
                           String title, String content, String language, String templateCode,
                           int templateVersion, String clinicalAuthorSubject, String dataEntrySubject,
                           String effectiveAt, String signedAt, String signerSubject, String attestation,
                           String contentChecksum, Integer supersedesVersion, String enteredErrorReason,
                           String enteredErrorActor, String affectedRecipients, String createdAt) {
}
