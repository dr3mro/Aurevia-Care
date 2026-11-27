/**
 * Pharmacy-proposed substitutions and their durable decision record.
 *
 * <p>This package owns the propose and decide commands, the role and party
 * matrix, the tenant-guarded JDBC store, the append-only signed revisions and
 * the port that applies an approved replacement. An idempotency claim is a
 * compare-and-set, and catalog and allergy facts are re-resolved at approval.</p>
 *
 * <p>The package must not widen clinician-gated catalog search to pharmacy
 * staff, decide allergy safety itself, or apply a substitution outside the
 * decision's transaction.</p>
 */
package com.aurevia.pharmacyflow.substitution;
