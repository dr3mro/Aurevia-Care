/**
 * Pharmacy status facts recorded against a prescription in the active tenant.
 *
 * <p>This package owns the bounded status vocabulary, field validation, the
 * permission check for recording a fact and the append-only in-process fact
 * trail. Dispensing evidence remains a separate fact from acceptance.</p>
 *
 * <p>The package must not mutate the prescribed medication, claim durable
 * persistence for its process-local trail, or accept roles from the request.</p>
 */
package com.aurevia.pharmacyflow.status;
