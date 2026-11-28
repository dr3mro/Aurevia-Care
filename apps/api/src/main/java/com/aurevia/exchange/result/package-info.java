/**
 * Inbound result routing, reconciliation decisions and their SQL boundary.
 *
 * <p>This package owns five-facet identifier matching, expectation registration,
 * payload-based idempotency, correction of the current lineage tip, routing to an
 * owner and coverage queue, and durable operator resolution of unmatched entries.
 * Imports are routed, never silently deduplicated by version alone; a divergent
 * payload under the same version is a conflict. Corrections target the lineage tip
 * and divert a stale target to reconciliation without rewriting history. The
 * resolution store is tenant-scoped and idempotent under concurrency: a unique key
 * arbitrates a retry and an empty result is a conflict the caller audits.</p>
 *
 * <p>Critical escalation and clinical acknowledgment belong to the critical-result
 * workflow, not here. The package must not derive tenant scope from request input,
 * update a queue status column in place of the durable decision fact, or weaken the
 * role boundary that keeps clinical content away from administrative reviewers.
 */
package com.aurevia.exchange.result;
