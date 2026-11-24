package com.aurevia.printing.web;

import java.time.Instant;

import com.aurevia.printing.HandoutKind;

/**
 * The sanitized facts recorded for one print, reprint, download or refusal (OPS-02).
 *
 * <p>The value deliberately has no field for clinical content, a patient name or
 * identifier, a file name, a URL or an object key: a disclosure is described by
 * who did it, what kind and version, why, in which facility and when — never by
 * what it contained. All components are immutable value types.</p>
 *
 * @param organizationId owning tenant identifier
 * @param actorSubject   authenticated subject that made the decision
 * @param kind           handout kind
 * @param handoutId      deterministic handout identifier, or the source id on a refusal
 * @param version        immutable handout version, or zero on a refusal
 * @param sourceRevision bound source revision
 * @param facility       facility of the record, not a caller preference
 * @param reason         approved reason for the disclosure
 * @param action         print, reprint, download or a named refusal action
 * @param outcome        {@code SUCCESS} or {@code DENIED}
 * @param at             server timestamp
 * @return this record
 */
public record PrintAuditEntry(String organizationId, String actorSubject, HandoutKind kind,
                              String handoutId, int version, int sourceRevision, String facility,
                              PrintReason reason, String action, String outcome, Instant at) {
}
