/**
 * FHIR R4 boundary projections and mappers for exchange, laboratory, imaging, document
 * and prescription aggregates.
 *
 * <p>This package owns the explicit set of fields that cross the FHIR wire, the shared JSON
 * primitives and the required-identifier checks applied on import. The partner profile name
 * is carried explicitly in the resource profile field; on import it is read from that declared
 * field and never guessed from unrelated resource content. A missing required patient, order
 * or specimen identifier is rejected rather than guessed.</p>
 *
 * <p>The package must not write to stores, call services, derive authorization or depend on
 * the HL7, conformance, Spring or persistence layers. Domain types remain authoritative: these
 * records are projections, not replacements for the domain aggregates.</p>
 */
package com.aurevia.exchange.fhir;
