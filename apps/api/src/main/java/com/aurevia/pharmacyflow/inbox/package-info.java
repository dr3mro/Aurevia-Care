/**
 * Tenant-scoped read model for prescriptions explicitly routed to a pharmacy.
 *
 * <p>This package owns the assigned-pharmacy equality check, the chart-free
 * prescription projection, cursor pagination and the audit of each read. A
 * prescription without a routed destination, or one routed to another pharmacy,
 * is invisible even inside the same tenant.</p>
 *
 * <p>The package must not read chart tables, derive general chart access from a
 * routed prescription, or change prescription state.</p>
 */
package com.aurevia.pharmacyflow.inbox;
