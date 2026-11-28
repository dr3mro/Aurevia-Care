/**
 * Clinical trend capture, comparability policy and visible exclusions.
 *
 * <p>This package owns observation capture with source provenance, versioned
 * effective-dated comparability policy, the pure eligibility analyzer and the
 * trend view. Every observation is either a comparable point or a visible
 * exclusion with a machine-readable reason: nothing is silently dropped.
 * Clinical reads and observation writes are clinician-only, while defining
 * policy is a separate tenant-administrator act.</p>
 *
 * <p>The package must not depend on the exchange work-item state machine or
 * ledger, or on transport and logging. It must not invent clinical thresholds,
 * convert reported units, silently discard observations or generate its own
 * clinical interpretation.</p>
 */
package com.aurevia.exchange.trend;
