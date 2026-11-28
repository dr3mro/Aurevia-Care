/**
 * Imaging report delivery, addendum revisions and acknowledgment receipts.
 *
 * <p>This package owns append-only report revisions, one fresh receipt per
 * addendum, the verified-referrer read boundary and immutable delivery facts.
 * Both unsigned paths emit a refusal fact and throw before any positive
 * delivery fact; for the initial report the interpreter role is checked first.
 * Acknowledged addenda never reopen.</p>
 *
 * <p>The package must not persist its in-process trail itself, publish an
 * outbox directly or let a non-referrer read referrer-facing facts.</p>
 */
package com.aurevia.radflow.report;
