/**
 * Appointment lifecycle, booking invariants, recurrence series, tenant-guarded scheduling persistence and administrator-safe configuration limits.
 *
 * <p>The package re-derives membership and facility scope for protected commands and keeps every lookup inside the active organization. It must not accept a tenant or actor from a request body, invent a booking slot outside the configured bounds, or duplicate the identity-proofing rules of the check-in package.</p>
 */
package com.aurevia.scheduling;
