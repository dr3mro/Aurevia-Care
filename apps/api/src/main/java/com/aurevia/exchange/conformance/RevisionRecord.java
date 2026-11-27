package com.aurevia.exchange.conformance;

/**
 * Immutable applied revision retained in the conformance ledger history.
 *
 * <p>A correction never replaces the prior record; it appends a new one and
 * marks that new revision as reopening review.</p>
 *
 * @param revision     per-aggregate version of the applied event
 * @param kind         kind that produced the revision
 * @param identifier   issuer-scoped identifier captured at apply time
 * @param payload      preserved synthetic content
 * @param reviewOpened whether this revision reopened clinical review
 */
public record RevisionRecord(long revision, EventKind kind, String identifier,
                             String payload, boolean reviewOpened) {
}
