/**
 * Exchange work items with three independent state channels.
 *
 * <p>This package owns the workflow, transmission and clinical-review channels,
 * the legal transition matrix, the explicit-command protocol with version
 * preconditions and compare-and-set updates, the append-only transition ledger,
 * clinician-bound review evidence and the scoped exchange role matrix. One
 * tenant-guarded transaction commits the state change, its event and its audit
 * record together. The review actor is derived by the database from the
 * authenticated transaction context, never accepted from a request body.</p>
 *
 * <p>The package must not depend on trend analysis, on observability or log
 * formatting, or on web plumbing beyond the controller's wire parsing. A
 * client-supplied actor, timestamp or reviewed revision must never enter a
 * state channel.</p>
 */
package com.aurevia.exchange.task;
