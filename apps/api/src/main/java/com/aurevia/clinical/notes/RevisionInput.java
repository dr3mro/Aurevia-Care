package com.aurevia.clinical.notes;

/**
 * Caller-supplied fields of a new immutable revision, handed to the SQL boundary.
 *
 * <p>Identity, version, checksum and actor columns are owned by the database in
 * V15; this record intentionally carries no such fields as authoritative values.</p>
 *
 * @param state                 lifecycle state of the new revision
 * @param patientId             patient whose chart owns the note
 * @param encounterId           optional encounter context
 * @param title                 revision title
 * @param content               clinical content
 * @param language              content language
 * @param templateCode          template identifier
 * @param templateVersion       template version
 * @param clinicalAuthorSubject clinically responsible author
 * @param effectiveAt           clinical effective instant
 * @param signedAt              signature instant supplied by the application clock
 * @param attestation           signer attestation when signature bearing
 * @param contentChecksum       SHA-256 recomputed by the application, re-derived by the database
 * @param supersedesVersion     prior version this revision supersedes
 * @param enteredErrorReason    entered-in-error reason
 * @param affectedRecipients    recipient identifiers as a JSON array
 * @return this record
 */
public record RevisionInput(NoteState state, String patientId, String encounterId, String title,
                            String content, String language, String templateCode, int templateVersion,
                            String clinicalAuthorSubject, String effectiveAt, String signedAt,
                            String attestation, String contentChecksum, Integer supersedesVersion,
                            String enteredErrorReason, String affectedRecipients) {
}
