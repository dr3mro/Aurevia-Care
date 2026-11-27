/**
 * Pharmacy-initiated clarification threads for routed prescriptions.
 *
 * <p>This package owns thread creation, prescriber answers, party-scoped reads,
 * idempotent retries and the strict overdue rule. The prescriber is resolved
 * from the prescription rather than from the request body.</p>
 *
 * <p>The package must not modify a prescription, send partner messages or
 * treat its process-local threads as durable storage.</p>
 */
package com.aurevia.pharmacyflow.clarify;
