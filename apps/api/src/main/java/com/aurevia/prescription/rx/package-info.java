/**
 * Controlled prescription transmission and locally printable documents.
 *
 * <p>This package owns the transmission state machine, prescriber attestation,
 * market and channel policy checks, one stable submission id reused by retries,
 * the immutable event trail and the print-ready document. An ambiguous send
 * cannot be retried until the partner reports the submission absent.</p>
 *
 * <p>The package must not invent a partner delivery, create chart content or
 * conflate a local print channel with electronic submission.</p>
 */
package com.aurevia.prescription.rx;
