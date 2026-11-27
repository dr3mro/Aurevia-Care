/**
 * Transactional critical-result workflow, append-only fact ledger and thin HTTP adapter.
 *
 * <p>This package owns the bounded action matrix, input validation, domain contract,
 * tenant-guarded SQL boundary and the commands that register, verify, assign, escalate,
 * read back, acknowledge, follow up and release a critical result. Every command re-derives
 * authorization. State-changing commands check a state precondition and commit their row
 * change, event and audit fact together; release and reads record facts without a state
 * transition. Delivery and patient release never satisfy a clinician acknowledgment.</p>
 *
 * <p>Actor and tenant come from the transaction context and database bindings, never from
 * request input. The controller only parses the body, path and version header; all invariants
 * live in the domain service. The package must not derive permissions from the client or add
 * a second persistence boundary.</p>
 */
package com.aurevia.exchange.critical;
