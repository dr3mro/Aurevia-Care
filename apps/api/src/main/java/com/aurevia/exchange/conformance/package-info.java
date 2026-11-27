/**
 * Deterministic, in-memory synthetic partner replay and receiver conformance rules.
 *
 * <p>This package owns the fixture catalog, immutable event model, wire adapters and the
 * ledger that enforces deduplication in the sender and type namespace, identifier matching
 * against the local aggregate, per-aggregate version ordering, bounded retry, dead-lettering
 * and immutable revision history. Only a committed effect consumes an event identity; a
 * transport acknowledgment or patient release never closes a clinical review.</p>
 *
 * <p>Everything here is synthetic and runs without a database, live partner, credential or
 * HTTP entry point. The package reuses the HL7 escape codec and the FHIR JSON primitives but
 * must not depend on Spring, JDBC, tenant context or other exchange slices.</p>
 */
package com.aurevia.exchange.conformance;
