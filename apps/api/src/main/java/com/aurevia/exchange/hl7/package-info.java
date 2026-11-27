/**
 * Bounded HL7 v2 codec, mapper and in-process exchange entry point.
 *
 * <p>This package owns parsing, escaping, timestamp conversion, validation and mapping of
 * the supported ORM order and ORU result messages into the existing internal commands, plus
 * the acknowledgment and control-identifier deduplication entry point. Sender authorization
 * facts for an order come from the caller because they cannot be trusted from the wire.</p>
 *
 * <p>The duplicate ledger is process-local and no durable replay protection is claimed here.
 * The package must not depend on Spring, JDBC, tenant context, the database or the FHIR and
 * conformance packages; it uses the JDK and the internal order and result command types.</p>
 */
package com.aurevia.exchange.hl7;
